package app.webcast.net;

import android.content.res.AssetManager;
import android.util.Log;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Principal;
import java.security.Provider;
import java.security.SecureRandom;
import java.security.cert.CertificateFactory;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;

import javax.net.ssl.KeyManager;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLServerSocketFactory;
import javax.net.ssl.X509ExtendedKeyManager;

import org.conscrypt.Conscrypt;

/**
 * Server-side TLS for API18. The platform JSSE cannot talk to current Chrome, so we use the
 * bundled Conscrypt 2.5.3 (BoringSSL) provider in engine-socket mode.
 *
 * The certificate is self-signed and embedded as PEM (cert.pem) plus an unencrypted PKCS#8 key
 * (key.pk8); this avoids depending on old Android keystore formats.
 */
public final class TlsContext {
  private static final String TAG = "WebCastTls";
  private static final String CERT_ASSET = "tls/cert.pem";
  private static final String KEY_ASSET = "tls/key.pk8";

  private TlsContext() {}

  public static SSLServerSocketFactory createServerSocketFactory(AssetManager assets) throws Exception {
    X509Certificate cert = loadCertificate(assets);
    PrivateKey key = loadPrivateKey(assets);
    Provider provider = Conscrypt.newProvider();
    SSLContext ctx = SSLContext.getInstance("TLS", provider);
    ctx.init(new KeyManager[] {new FixedKeyManager(cert, key)}, null, new SecureRandom());
    SSLServerSocketFactory factory = ctx.getServerSocketFactory();
    try {
      Conscrypt.setUseEngineSocket(factory, true);
    } catch (Throwable t) {
      Log.w(TAG, "engine socket mode unavailable, using default", t);
    }
    Log.i(TAG, "TLS ready with " + provider.getName() + " " + provider.getVersion());
    return factory;
  }

  private static X509Certificate loadCertificate(AssetManager assets) throws Exception {
    InputStream in = assets.open(CERT_ASSET);
    try {
      CertificateFactory cf = CertificateFactory.getInstance("X.509");
      return (X509Certificate) cf.generateCertificate(in);
    } finally {
      closeQuietly(in);
    }
  }

  private static PrivateKey loadPrivateKey(AssetManager assets) throws Exception {
    byte[] der = readAll(assets.open(KEY_ASSET));
    return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der));
  }

  private static byte[] readAll(InputStream in) throws Exception {
    try {
      ByteArrayOutputStream out = new ByteArrayOutputStream(4096);
      byte[] buf = new byte[4096];
      int n;
      while ((n = in.read(buf)) > 0) out.write(buf, 0, n);
      return out.toByteArray();
    } finally {
      closeQuietly(in);
    }
  }

  private static void closeQuietly(InputStream in) {
    if (in != null) try { in.close(); } catch (Exception ignored) {}
  }

  /** One fixed RSA certificate: no keystore loading, no password, no KeyManagerFactory quirks. */
  private static final class FixedKeyManager extends X509ExtendedKeyManager {
    private final X509Certificate cert;
    private final PrivateKey key;
    private static final String ALIAS = "webcast";

    FixedKeyManager(X509Certificate cert, PrivateKey key) {
      this.cert = cert;
      this.key = key;
    }

    @Override public String[] getClientAliases(String keyType, Principal[] issuers) { return null; }
    @Override public String chooseClientAlias(String[] keyType, Principal[] issuers, java.net.Socket socket) { return null; }
    @Override public String[] getServerAliases(String keyType, Principal[] issuers) { return new String[] {ALIAS}; }
    @Override public String chooseServerAlias(String keyType, Principal[] issuers, java.net.Socket socket) { return ALIAS; }
    @Override public X509Certificate[] getCertificateChain(String alias) { return new X509Certificate[] {cert}; }
    @Override public PrivateKey getPrivateKey(String alias) { return key; }
    @Override public String chooseEngineClientAlias(String[] keyType, Principal[] issuers, javax.net.ssl.SSLEngine engine) { return null; }
    @Override public String chooseEngineServerAlias(String keyType, Principal[] issuers, javax.net.ssl.SSLEngine engine) { return ALIAS; }
  }
}
