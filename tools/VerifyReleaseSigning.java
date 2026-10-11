import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.cert.X509Certificate;
import java.util.HexFormat;
import java.util.Properties;

/** Optional publisher check; never invoked by Demo Gradle builds. Requires JDK 17. */
class VerifyReleaseSigning {
    public static void main(String[] args) {
        String stage = "configuration";
        try {
            Path root = Path.of(args.length == 0 ? "." : args[0]).toAbsolutePath();
            Properties config = new Properties();
            try (var input = Files.newInputStream(root.resolve("release-signing.properties"))) {
                config.load(input);
            }
            String storePassword = password(config, "storePassword", "ROBOYI_RELEASE_STORE_PASSWORD");
            String keyPassword = password(config, "keyPassword", "ROBOYI_RELEASE_KEY_PASSWORD");
            if (storePassword.isEmpty() || keyPassword.isEmpty()) throw new IllegalArgumentException("Missing local signing passwords");
            KeyStore store = KeyStore.getInstance(config.getProperty("storeType", "pkcs12").trim());
            stage = "keystore password and format";
            try (var input = Files.newInputStream(root.resolve(config.getProperty("storeFile").trim()))) {
                store.load(input, storePassword.toCharArray());
            }
            String alias = config.getProperty("keyAlias").trim();
            stage = "private key alias and password";
            if (!(store.getKey(alias, keyPassword.toCharArray()) instanceof PrivateKey)) {
                throw new IllegalArgumentException("Alias has no accessible private key");
            }
            X509Certificate certificate = (X509Certificate) store.getCertificate(alias);
            stage = "certificate validity";
            certificate.checkValidity();
            String subject = certificate.getSubjectX500Principal().getName();
            stage = "release certificate identity";
            if (subject.contains("CN=Android Debug")) throw new IllegalArgumentException("Debug signing certificate");
            System.out.println("Release private key and certificate verified");
            System.out.println("Subject: " + subject);
            System.out.println("Valid until: " + certificate.getNotAfter());
            System.out.println("Certificate SHA-256: " + HexFormat.of().formatHex(
                MessageDigest.getInstance("SHA-256").digest(certificate.getEncoded())));
        } catch (Exception error) {
            // Do not echo passwords, token values or nested keystore exceptions.
            System.err.println("Release signing verification failed at " + stage + " (" + error.getClass().getSimpleName() + "). Check the local configuration.");
            if (error.getCause() != null) System.err.println("Cause type: " + error.getCause().getClass().getSimpleName());
            System.exit(1);
        }
    }
    private static String password(Properties config, String name, String environmentName) {
        String value = System.getenv(environmentName);
        return value != null && !value.isEmpty() ? value : config.getProperty(name, "");
    }
}
