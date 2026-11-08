package com.superagent.files;

import com.superagent.api.file.FileStorageDto;
import com.superagent.core.file.FileStore;
import com.superagent.core.file.FileStoreProvider;
import com.superagent.core.file.WorkFile;
import com.superagent.core.file.WorkFileStore;
import com.superagent.core.security.SecretCipher;
import com.superagent.core.settings.SystemSettingStore;
import com.superagent.core.tenant.TenantContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Where Files keeps content. A <b>storage channel</b> is one kind of store (a plugin) at one fixed location — the
 * local disk, or a given bucket and prefix. Every file records its channel ({@link WorkFile#storage()}), and a
 * channel's location never changes: saving settings that point somewhere else opens a new channel and makes it the
 * one new files go to, while earlier channels stay readable for the files in them. Changing only credentials (a
 * rotated key) updates the channel in place.
 *
 * <p>Kept in the system settings: {@code files.storage.active}, {@code files.storage.channels} (the ids), and per
 * channel {@code files.storage.channel.<id>.type|created|<field>}; secrets encrypted.
 */
@Service
public class FileStorageService {

    private static final Logger log = LoggerFactory.getLogger(FileStorageService.class);
    private static final String ACTIVE = "files.storage.active";
    private static final String CHANNELS = "files.storage.channels";

    private final Map<String, FileStoreProvider> providers = new LinkedHashMap<>();
    private final SystemSettingStore settings;
    private final SecretCipher cipher;
    private final WorkFileStore files;
    /** Open stores by company and channel: channel ids (a kind's name, at first) repeat across companies. */
    private final Map<String, FileStore> open = new ConcurrentHashMap<>();
    private final Object lock = new Object();

    public FileStorageService(List<FileStoreProvider> installed, SystemSettingStore settings, SecretCipher cipher,
                              WorkFileStore files) {
        installed.stream().sorted(Comparator.comparing((FileStoreProvider p) -> !WorkFile.INTERNAL.equals(p.type()))
                .thenComparing(FileStoreProvider::type)).forEach(p -> providers.put(p.type(), p));
        this.settings = settings;
        this.cipher = cipher;
        this.files = files;
    }

    /** One storage channel as saved. */
    private record Channel(String id, String type, Map<String, String> values, Instant createdAt) {
    }

    // --- for Files ---

    /** The channel new files go to: the chosen one, or the local disk (also when the chosen one's plugin is gone). */
    public String activeChannel() {
        String id = blank(settings.all().get(ACTIVE));
        if (id == null) {
            return WorkFile.INTERNAL;
        }
        Optional<Channel> c = channel(id);
        if (c.isEmpty() || !providers.containsKey(c.get().type())) {
            log.warn("file storage channel {} is chosen but unavailable; new files go to the local disk", id);
            return WorkFile.INTERNAL;
        }
        return id;
    }

    public FileStore active() {
        return store(activeChannel());
    }

    /** The store a channel's content is in, opened with that channel's own settings. */
    public FileStore store(String channelId) {
        return open.computeIfAbsent(openKey(channelId), k -> {
            String id = channelId;
            Channel c = channel(id).orElseThrow(() -> new IllegalStateException("没有这个文件存储渠道：" + id));
            FileStoreProvider p = providers.get(c.type());
            if (p == null) {
                throw new IllegalStateException("文件存储插件未安装：" + c.type());
            }
            return p.open(decrypted(p, c.values()));
        });
    }

    // --- for the admin page ---

    public FileStorageDto view() {
        String active = activeChannel();
        List<Channel> all = channels();
        Map<String, Long> counts = files.countByStorage();
        return new FileStorageDto(active, channel(active).map(Channel::type).orElse(WorkFile.INTERNAL),
                providers.values().stream().map(p -> {
                    Map<String, String> from = formSource(all.stream().filter(c -> c.type().equals(p.type())).toList())
                            .map(Channel::values).orElse(Map.of());
                    return new FileStorageDto.Provider(p.type(), p.label(), p.fields().stream().map(f -> {
                        String v = from.get(f.key());
                        return new FileStorageDto.Field(f.key(), f.label(), f.secret(), f.required(), f.placeholder(),
                                f.secret() ? null : v, v != null, f.locates());
                    }).toList());
                }).toList(),
                all.stream().map(c -> new FileStorageDto.Channel(c.id(), c.type(), location(c), c.id().equals(active),
                        counts.getOrDefault(c.id(), 0L), c.createdAt())).toList());
    }

    /** Writes, reads back and removes a small file with these settings; throws with what went wrong. */
    public void test(String type, Map<String, String> values) {
        FileStoreProvider p = provider(type);
        probe(p.open(decrypted(p, merged(p, values, base(p, values)))));
    }

    /**
     * Checks the settings work, then makes them where new files go: the channel at that location if there is one
     * (its credentials updated), else a new channel. Existing files stay in their own channels.
     */
    public FileStorageDto save(String type, Map<String, String> values) {
        FileStoreProvider p = provider(type);
        synchronized (lock) {
            Optional<Channel> base = base(p, values);
            Map<String, String> stored = merged(p, values, base);
            probe(p.open(decrypted(p, stored)));
            String id;
            if (p.fields().isEmpty()) {
                id = p.type(); // a kind without settings (the local disk) is a single channel
            } else if (base.isPresent() && sameLocation(p, base.get().values(), stored)) {
                id = base.get().id();
            } else {
                id = p.type() + "-" + UUID.randomUUID().toString().substring(0, 6);
            }
            write(new Channel(id, p.type(), stored, base.filter(b -> b.id().equals(id)).map(Channel::createdAt)
                    .orElse(Instant.now())));
            settings.put(ACTIVE, id);
            open.remove(openKey(id)); // reopened with the new settings
            log.info("new files now go to storage channel {} ({})", id, p.type());
        }
        return view();
    }

    // --- channels ---

    private List<Channel> channels() {
        List<Channel> out = new ArrayList<>();
        for (String id : ids()) {
            channel(id).ifPresent(out::add);
        }
        return out;
    }

    private List<String> ids() {
        String raw = blank(settings.all().get(CHANNELS));
        return raw == null ? List.of(WorkFile.INTERNAL) : Arrays.stream(raw.split(",")).filter(s -> !s.isBlank()).toList();
    }

    private Optional<Channel> channel(String id) {
        if (WorkFile.INTERNAL.equals(id)) {
            return Optional.of(new Channel(WorkFile.INTERNAL, WorkFile.INTERNAL, Map.of(), Instant.EPOCH));
        }
        Map<String, String> all = settings.all();
        String type = blank(all.get(key(id, "type")));
        if (type == null) {
            return Optional.empty();
        }
        Map<String, String> values = new LinkedHashMap<>();
        FileStoreProvider p = providers.get(type);
        if (p != null) {
            p.fields().forEach(f -> {
                String v = blank(all.get(key(id, f.key())));
                if (v != null) {
                    values.put(f.key(), v);
                }
            });
        }
        String created = blank(all.get(key(id, "created")));
        return Optional.of(new Channel(id, type, values, created == null ? Instant.EPOCH : Instant.parse(created)));
    }

    private void write(Channel c) {
        if (WorkFile.INTERNAL.equals(c.id())) {
            return;
        }
        settings.put(key(c.id(), "type"), c.type());
        settings.put(key(c.id(), "created"), c.createdAt().toString());
        providers.get(c.type()).fields().forEach(f -> settings.put(key(c.id(), f.key()), c.values().get(f.key())));
        List<String> ids = new ArrayList<>(ids());
        if (!ids.contains(c.id())) {
            ids.add(c.id());
            settings.put(CHANNELS, String.join(",", ids));
        }
    }

    /**
     * What the given settings build on: the channel of this kind already at that location (switching back to it, or
     * rotating its key), else the channel in use when it's this kind, else this kind's most recent — the form was
     * filled from the same one, so its "blank keeps the saved secret" means that channel's secret.
     */
    private Optional<Channel> base(FileStoreProvider p, Map<String, String> values) {
        List<Channel> mine = channels().stream().filter(c -> c.type().equals(p.type())).toList();
        Map<String, String> given = values == null ? Map.of() : values;
        return mine.stream().filter(c -> sameLocation(p, c.values(), given)).findFirst()
                .or(() -> formSource(mine));
    }

    private Optional<Channel> formSource(List<Channel> ofOneKind) {
        String active = activeChannel();
        return ofOneKind.stream().filter(c -> c.id().equals(active)).findFirst()
                .or(() -> ofOneKind.stream().max(Comparator.comparing(Channel::createdAt)));
    }

    private static boolean sameLocation(FileStoreProvider p, Map<String, String> a, Map<String, String> b) {
        return p.fields().stream().filter(FileStoreProvider.Field::locates)
                .allMatch(f -> Objects.equals(blank(a.get(f.key())), blank(b.get(f.key()))));
    }

    /** The settings to store: as given, a blank secret keeping the base's; required ones must be there. */
    private Map<String, String> merged(FileStoreProvider p, Map<String, String> values, Optional<Channel> base) {
        Map<String, String> out = new LinkedHashMap<>();
        for (FileStoreProvider.Field f : p.fields()) {
            String v = blank(values == null ? null : values.get(f.key()));
            if (f.secret()) {
                v = v != null ? cipher.encrypt(v) : base.map(b -> b.values().get(f.key())).orElse(null);
            }
            if (v == null && f.required()) {
                throw new IllegalArgumentException("请填写「" + f.label() + "」");
            }
            if (v != null) {
                out.put(f.key(), v);
            }
        }
        return out;
    }

    private Map<String, String> decrypted(FileStoreProvider p, Map<String, String> stored) {
        Map<String, String> out = new HashMap<>(stored);
        p.fields().stream().filter(FileStoreProvider.Field::secret).forEach(f -> {
            if (out.containsKey(f.key())) {
                out.put(f.key(), cipher.decrypt(out.get(f.key())));
            }
        });
        return out;
    }

    private Map<String, String> location(Channel c) {
        FileStoreProvider p = providers.get(c.type());
        Map<String, String> out = new LinkedHashMap<>();
        if (p != null) {
            p.fields().stream().filter(FileStoreProvider.Field::locates)
                    .forEach(f -> { if (c.values().containsKey(f.key())) out.put(f.key(), c.values().get(f.key())); });
        }
        return out;
    }

    private static String openKey(String channelId) {
        return TenantContext.require() + "/" + channelId;
    }

    private FileStoreProvider provider(String type) {
        FileStoreProvider p = type == null ? null : providers.get(type);
        if (p == null) {
            throw new IllegalArgumentException("Unknown storage type: " + type);
        }
        return p;
    }

    private static void probe(FileStore store) {
        String id = "superagent-probe-" + UUID.randomUUID();
        Path tmp = null;
        try {
            tmp = Files.createTempFile("superagent-probe-", ".txt");
            byte[] body = ("superagent storage check " + id).getBytes(StandardCharsets.UTF_8);
            Files.write(tmp, body);
            store.put(id, tmp, "text/plain");
            try (InputStream in = store.open(id)) {
                if (!Arrays.equals(in.readAllBytes(), body)) {
                    throw new IllegalArgumentException("存储连接测试失败：读回的内容与写入的不一致");
                }
            }
            store.delete(id);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (IOException | RuntimeException e) {
            throw new IllegalArgumentException("存储连接测试失败：" + (e.getMessage() == null ? e.getClass().getSimpleName()
                    : e.getMessage()), e);
        } finally {
            if (tmp != null) {
                try {
                    Files.deleteIfExists(tmp);
                } catch (IOException ignored) {
                    // a temp file left behind is harmless
                }
            }
        }
    }

    private static String key(String channelId, String field) {
        return "files.storage.channel." + channelId + "." + field;
    }

    private static String blank(String v) {
        return v == null || v.isBlank() ? null : v.strip();
    }
}
