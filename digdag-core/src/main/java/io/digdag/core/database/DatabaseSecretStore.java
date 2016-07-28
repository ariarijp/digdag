package io.digdag.core.database;

import com.google.common.collect.ImmutableMap;
import io.digdag.core.SecretCrypto;
import io.digdag.spi.SecretAccessContext;
import io.digdag.spi.SecretAccessDeniedException;
import io.digdag.spi.SecretScopes;
import io.digdag.spi.SecretStore;
import org.skife.jdbi.v2.DBI;
import org.skife.jdbi.v2.sqlobject.Bind;
import org.skife.jdbi.v2.sqlobject.SqlQuery;

import java.util.List;
import java.util.Map;
import java.util.Optional;

class DatabaseSecretStore
        extends BasicDatabaseStoreManager<DatabaseSecretStore.Dao>
        implements SecretStore
{
    private static final Map<String, Integer> PRIORITIES = ImmutableMap.of(
            SecretScopes.PROJECT, 0,
            SecretScopes.USER_DEFAULT, 1);

    private final int siteId;

    private final SecretCrypto crypto;

    DatabaseSecretStore(DatabaseConfig config, DBI dbi, int siteId, SecretCrypto crypto)
    {
        super(config.getType(), Dao.class, dbi);
        this.siteId = siteId;
        this.crypto = crypto;
    }

    @Override
    public String getSecret(SecretAccessContext context, String key)
    {
        if (context.siteId() != siteId) {
            throw new SecretAccessDeniedException("Site id mismatch");
        }

        List<ScopedSecret> secrets = autoCommit((handle, dao) -> dao.getProjectSecrets(siteId, context.projectId(), key));

        Optional<ScopedSecret> secret = secrets.stream()
                .filter(s -> PRIORITIES.containsKey(s.scope))
                .sorted((a, b) -> PRIORITIES.get(a.scope) - PRIORITIES.get(b.scope))
                .findFirst();

        if (!secret.isPresent()) {
            return null;
        }

        return crypto.decryptSecret(secret.get().value);
    }

    interface Dao
    {
        @SqlQuery("select scope, value from secrets" +
                " where site_id = :siteId and project_id = :projectId and key = :key")
        List<ScopedSecret> getProjectSecrets(@Bind("siteId") int siteId, @Bind("projectId") int projectId, @Bind("key") String key);
    }

    private static class ScopedSecret
    {
        private final String scope;
        private final String value;

        private ScopedSecret(String scope, String value)
        {
            this.scope = scope;
            this.value = value;
        }
    }
}
