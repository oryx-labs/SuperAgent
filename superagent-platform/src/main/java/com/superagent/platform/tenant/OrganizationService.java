package com.superagent.platform.tenant;

import com.superagent.core.error.ConflictException;
import com.superagent.core.tenant.TenantContext;
import com.superagent.core.user.User;
import com.superagent.core.user.UserSource;
import com.superagent.core.user.UserStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.Instant;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Organizations: anyone can register one with its name and an English name, and gets its admin account with a
 * generated password, shown once. The English name is the organization's identifier: what everyone types at sign-in
 * to say which organization they belong to, and the admin's username. There is no built-in account: the first
 * organization registered owns the host (see {@link CompanyService#isHost}).
 */
@Service
public class OrganizationService {

    private static final Logger log = LoggerFactory.getLogger(OrganizationService.class);

    /** The English name: lowercase letters, digits and inner hyphens; starts with a letter. */
    static final Pattern SHORT_NAME = Pattern.compile("[a-z][a-z0-9-]{1,30}[a-z0-9]");
    private static final Set<String> RESERVED = Set.of("admin", "api", "www", "root", "system", "login", "mcp",
            "static", "support", "help", "default", "platform");
    private static final String PASSWORD_CHARS = "ABCDEFGHJKLMNPQRSTUVWXYZabcdefghijkmnopqrstuvwxyz23456789";

    private final CompanyService companies;
    private final UserStore users;
    private final PasswordEncoder encoder;
    private final SecureRandom random = new SecureRandom();

    /** What a new organization's admin needs to sign in; the password is shown once and never kept in clear. */
    public record Registered(String org, String name, String username, String password) {
    }

    /** Whether anyone may register an organization from the sign-in page (superagent.registration.enabled). */
    private final boolean registrationOpen;

    public OrganizationService(CompanyService companies, UserStore users, PasswordEncoder encoder) {
        this(companies, users, encoder, true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OrganizationService(CompanyService companies, UserStore users, PasswordEncoder encoder,
                               @org.springframework.beans.factory.annotation.Value("${superagent.registration.enabled:true}")
                               boolean registrationOpen) {
        this.registrationOpen = registrationOpen;
        this.companies = companies;
        this.users = users;
        this.encoder = encoder;
    }

    public boolean registrationOpen() {
        return registrationOpen;
    }

    /** Registers an organization and its admin; the admin's username is its English name. */
    public Registered register(String name, String englishName) {
        if (!registrationOpen) {
            throw new RegistrationClosedException();
        }
        String clean = cleanName(name);
        String org = englishName == null ? "" : englishName.strip().toLowerCase(Locale.ROOT);
        if (org.isEmpty()) {
            throw new IllegalArgumentException("请填写组织的英文名");
        }
        if (!valid(org)) {
            throw new IllegalArgumentException("英文名只能用小写字母、数字和连字符，以字母开头，3 到 32 位，例如 acme 或 acme-china");
        }
        if (RESERVED.contains(org)) {
            throw new IllegalArgumentException("英文名「" + org + "」是保留词，请换一个");
        }
        String password = newPassword();
        synchronized (this) {
            if (companies.exists(org)) {
                throw new ShortNameTakenException(org);
            }
            companies.create(org, clean);
            TenantContext.run(org, () -> users.save(admin(org, clean, password)));
        }
        log.info("organization {} ({}) registered", org, clean);
        return new Registered(org, clean, org, password);
    }

    private User admin(String org, String orgName, String password) {
        Instant now = Instant.now();
        return new User(UUID.randomUUID().toString(), org, orgName + " 管理员", Set.of("ADMIN"), true,
                encoder.encode(password), null, null, UserSource.LOCAL, now, now, true);
    }

    private String newPassword() {
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < 14; i++) {
            sb.append(PASSWORD_CHARS.charAt(random.nextInt(PASSWORD_CHARS.length())));
        }
        return sb.toString();
    }

    private static String cleanName(String name) {
        String n = name == null ? "" : name.strip();
        if (n.isEmpty() || n.length() > 64) {
            throw new IllegalArgumentException("请填写组织名称（最多 64 个字）");
        }
        return n;
    }

    static boolean valid(String s) {
        return s != null && SHORT_NAME.matcher(s).matches();
    }

    /** Registration is turned off on this server (superagent.registration.enabled=false). */
    public static class RegistrationClosedException extends com.superagent.core.error.ForbiddenException {
        RegistrationClosedException() {
            super("这台服务器没有开放注册组织，请联系管理员开通");
        }
    }

    /** That English name is already some organization's. */
    public static class ShortNameTakenException extends ConflictException {
        ShortNameTakenException(String org) {
            super("英文名「" + org + "」已被其他组织使用，请换一个");
        }
    }
}
