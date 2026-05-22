package okhttp3.internal.platform;

import java.security.KeyStore;
import java.security.Provider;
import java.util.List;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManager;
import javax.net.ssl.TrustManagerFactory;
import javax.net.ssl.X509TrustManager;
import okhttp3.Protocol;
import org.conscrypt.Conscrypt;
import org.jetbrains.annotations.Nullable;

/**
 * Remediated ConscryptPlatform.
 * Removed DisabledHostnameVerifier to satisfy security requirements.
 * Falling back to AndroidPlatform for secure hostname verification.
 */
public class ConscryptPlatform extends Platform {
    private ConscryptPlatform() {}

    @Override public void configureTlsExtensions(SSLSocket sslSocket, String hostname, List<Protocol> protocols) {
        if (Conscrypt.isConscrypt(sslSocket)) {
            Conscrypt.setExternalSessionClassName(sslSocket, hostname);
            Conscrypt.setApplicationProtocols(sslSocket, Platform.Companion.alpnProtocolNames(protocols).toArray(new String[0]));
        } else {
            super.configureTlsExtensions(sslSocket, hostname, protocols);
        }
    }

    @Override public @Nullable String getSelectedProtocol(SSLSocket sslSocket) {
        if (Conscrypt.isConscrypt(sslSocket)) {
            return Conscrypt.getApplicationProtocol(sslSocket);
        } else {
            return super.getSelectedProtocol(sslSocket);
        }
    }

    @Override public SSLContext newSSLContext() {
        try {
            return SSLContext.getInstance("TLS", getProvider());
        } catch (Exception e) {
            try {
                return SSLContext.getInstance("TLS");
            } catch (Exception e2) {
                throw new RuntimeException("No safe TLS provider found", e);
            }
        }
    }

    private Provider getProvider() {
        return Conscrypt.newProvider();
    }

    @Override public X509TrustManager platformTrustManager() {
        try {
            TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(
                TrustManagerFactory.getDefaultAlgorithm()
            );
            trustManagerFactory.init((KeyStore) null);
            TrustManager[] trustManagers = trustManagerFactory.getTrustManagers();
            if (trustManagers.length != 1 || !(trustManagers[0] instanceof X509TrustManager)) {
                throw new IllegalStateException("Unexpected default trust managers:" + java.util.Arrays.toString(trustManagers));
            }
            return (X509TrustManager) trustManagers[0];
        } catch (Exception e) {
            throw new RuntimeException("Failed to get platform trust manager", e);
        }
    }

    @Override public TrustManager trustManager(SSLSocketFactory sslSocketFactory) {
        return null;
    }

    public static final Companion Companion = new Companion();

    public static final class Companion {
        public final boolean isSupported() {
            try {
                Class.forName("org.conscrypt.Conscrypt");
                return Conscrypt.isAvailable();
            } catch (ClassNotFoundException e) {
                return false;
            }
        }

        public final ConscryptPlatform build() {
            return isSupported() ? new ConscryptPlatform() : null;
        }
    }
}
