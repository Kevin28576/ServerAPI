package kevin.serverapi;

import io.papermc.paper.plugin.loader.PluginClasspathBuilder;
import io.papermc.paper.plugin.loader.PluginLoader;
import io.papermc.paper.plugin.loader.library.impl.MavenLibraryResolver;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.repository.RemoteRepository;

/**
 * Paper PluginLoader：於執行期自 Maven Central 下載資料庫/快取相關函式庫，
 * 加入插件 classpath，避免把驅動打包進 jar（jar 保持精簡）。
 *
 * 於 paper-plugin.yml 以 `loader:` 指定本類別。
 */
public class ServerAPILibraries implements PluginLoader {

    @Override
    public void classloader(PluginClasspathBuilder classpathBuilder) {
        MavenLibraryResolver resolver = new MavenLibraryResolver();
        // 必須使用 Paper 提供的官方鏡像常數：
        // 直接把 Maven Central 當 CDN 違反其服務條款，Paper 會直接擲出例外拒絕載入。
        resolver.addRepository(new RemoteRepository.Builder(
                "central", "default", MavenLibraryResolver.MAVEN_CENTRAL_DEFAULT_MIRROR).build());

        String[] coords = {
                "com.zaxxer:HikariCP:5.1.0",
                "org.xerial:sqlite-jdbc:3.46.1.3",
                "com.mysql:mysql-connector-j:9.0.0",
                "org.mariadb.jdbc:mariadb-java-client:3.4.1",
                "redis.clients:jedis:5.1.5"
        };
        for (String coord : coords) {
            resolver.addDependency(new Dependency(new DefaultArtifact(coord), null));
        }

        classpathBuilder.addLibrary(resolver);
    }
}
