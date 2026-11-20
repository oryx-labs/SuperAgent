package com.superagent.api.provider;

import java.util.List;

/**
 * The result of importing the keys this server already had (the CLI's own config, the environment) into the
 * company's pool. {@code found} counts the keys on the machine, {@code imported} the ones added now, {@code kept}
 * the ones already in the pool and {@code deleted} the ones an admin deleted earlier (those are not added back);
 * {@code sources} says where a key would have been found, so "nothing there" comes with what to look at;
 * {@code credentials} is the pool afterwards.
 */
public record CredentialImportDto(int found, int imported, int kept, int deleted, List<String> sources,
                                  List<CredentialDto> credentials) {
}
