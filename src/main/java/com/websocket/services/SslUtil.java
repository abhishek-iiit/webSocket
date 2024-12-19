package com.websocket.services;

import org.bouncycastle.cert.X509CertificateHolder;
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.bouncycastle.openssl.PEMParser;
import org.bouncycastle.openssl.jcajce.JcaPEMKeyConverter;
import org.bouncycastle.openssl.jcajce.JceOpenSSLPKCS8DecryptorProviderBuilder;
import org.bouncycastle.operator.InputDecryptorProvider;
import org.bouncycastle.pkcs.PKCS8EncryptedPrivateKeyInfo;
import org.bouncycastle.pkcs.PKCSException;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocketFactory;
import javax.net.ssl.TrustManagerFactory;
import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.security.KeyStore;
import java.security.PrivateKey;
import java.security.Security;
import java.security.cert.Certificate;
import java.security.cert.X509Certificate;

@Service
public class SslUtil {
    private static org.slf4j.Logger logger = LoggerFactory.getLogger(SslUtil.class);
    public static Certificate loadCertificate(InputStream caCertStream) throws Exception {
        try (PEMParser pemParser = new PEMParser(new InputStreamReader(caCertStream))) {
            X509CertificateHolder certificateHolder = (X509CertificateHolder) pemParser.readObject();
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certificateHolder);
        }
    }

    public static PrivateKey loadPrivateKey(InputStream privateKeyStream, char[] password) throws Exception {
        try (PEMParser pemParser = new PEMParser(new InputStreamReader(privateKeyStream))) {
            Object keyObject = pemParser.readObject();
            JcaPEMKeyConverter keyConverter = new JcaPEMKeyConverter().setProvider("BC");

            if (keyObject instanceof PKCS8EncryptedPrivateKeyInfo) {
                PKCS8EncryptedPrivateKeyInfo encryptedPrivateKeyInfo = (PKCS8EncryptedPrivateKeyInfo) keyObject;
                InputDecryptorProvider decryptorProvider = new JceOpenSSLPKCS8DecryptorProviderBuilder().build(password);
                return keyConverter.getPrivateKey(encryptedPrivateKeyInfo.decryptPrivateKeyInfo(decryptorProvider));
            } else if (keyObject instanceof org.bouncycastle.asn1.pkcs.PrivateKeyInfo) {
                return keyConverter.getPrivateKey((org.bouncycastle.asn1.pkcs.PrivateKeyInfo) keyObject);
            } else {
                throw new IllegalArgumentException("Unsupported private key format");
            }
        } catch (PKCSException e) {
            throw new IllegalArgumentException("Invalid private key or incorrect password", e);
        }
    }

    public static X509Certificate loadX509Certificate(InputStream certStream) throws Exception {
        try (PEMParser pemParser = new PEMParser(new InputStreamReader(certStream))) {
            X509CertificateHolder certificateHolder = (X509CertificateHolder) pemParser.readObject();
            return new JcaX509CertificateConverter().setProvider("BC").getCertificate(certificateHolder);
        }
    }

    public static SSLSocketFactory getSocketFactory(byte[] caCertBytes, InputStream clientCertStream, InputStream clientKeyStream) throws Exception {
        Security.addProvider(new BouncyCastleProvider());
        Certificate ca = loadCertificate(new ByteArrayInputStream(caCertBytes));
        PrivateKey privateKey = loadPrivateKey(clientKeyStream, null);
        X509Certificate clientCertificate = loadX509Certificate(clientCertStream);

        KeyStore keyStore = KeyStore.getInstance(KeyStore.getDefaultType());
        keyStore.load(null, null);
        keyStore.setCertificateEntry("ca-cert", ca);
        keyStore.setKeyEntry("client-cert", privateKey, null, new Certificate[]{clientCertificate});

        TrustManagerFactory trustManagerFactory = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        trustManagerFactory.init(keyStore);

        KeyManagerFactory keyManagerFactory = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        keyManagerFactory.init(keyStore, null);

        SSLContext context = SSLContext.getInstance("TLS");
        context.init(keyManagerFactory.getKeyManagers(), trustManagerFactory.getTrustManagers(), null);
        return context.getSocketFactory();
    }
}


