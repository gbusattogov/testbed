package com.secunet.bouncycastle.crypto.tls;

import java.io.IOException;
import java.util.Hashtable;

import com.secunet.bouncycastle.crypto.tls.AbstractTlsClient;
import com.secunet.bouncycastle.crypto.tls.AlertDescription;
import com.secunet.bouncycastle.crypto.tls.CipherSuite;
import com.secunet.bouncycastle.crypto.tls.DefaultTlsCipherFactory;
import com.secunet.bouncycastle.crypto.tls.DefaultTlsSRPGroupVerifier;
import com.secunet.bouncycastle.crypto.tls.EncryptionAlgorithm;
import com.secunet.bouncycastle.crypto.tls.KeyExchangeAlgorithm;
import com.secunet.bouncycastle.crypto.tls.MACAlgorithm;
import com.secunet.bouncycastle.crypto.tls.TlsAuthentication;
import com.secunet.bouncycastle.crypto.tls.TlsCipher;
import com.secunet.bouncycastle.crypto.tls.TlsCipherFactory;
import com.secunet.bouncycastle.crypto.tls.TlsExtensionsUtils;
import com.secunet.bouncycastle.crypto.tls.TlsFatalAlert;
import com.secunet.bouncycastle.crypto.tls.TlsKeyExchange;
import com.secunet.bouncycastle.crypto.tls.TlsSRPGroupVerifier;
import com.secunet.bouncycastle.crypto.tls.TlsSRPKeyExchange;
import com.secunet.bouncycastle.crypto.tls.TlsSRPUtils;
import com.secunet.bouncycastle.crypto.tls.TlsUtils;
import org.bouncycastle.util.Arrays;

public class SRPTlsClient
    extends AbstractTlsClient
{
    protected TlsSRPGroupVerifier groupVerifier;

    protected byte[] identity;
    protected byte[] password;

    public SRPTlsClient(byte[] identity, byte[] password)
    {
        this(new DefaultTlsCipherFactory(), new DefaultTlsSRPGroupVerifier(), identity, password);
    }

    public SRPTlsClient(TlsCipherFactory cipherFactory, byte[] identity, byte[] password)
    {
        this(cipherFactory, new DefaultTlsSRPGroupVerifier(), identity, password);
    }

    public SRPTlsClient(TlsCipherFactory cipherFactory, TlsSRPGroupVerifier groupVerifier,
        byte[] identity, byte[] password)
    {
        super(cipherFactory);
        this.groupVerifier = groupVerifier;
        this.identity = Arrays.clone(identity);
        this.password = Arrays.clone(password);
    }

    protected boolean requireSRPServerExtension()
    {
        // No explicit guidance in RFC 5054; by default an (empty) extension from server is optional
        return false;
    }

    public int[] getCipherSuites()
    {
        return new int[]
        {
            CipherSuite.TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA
        };
    }

    public Hashtable getClientExtensions()
        throws IOException
    {
        Hashtable clientExtensions = TlsExtensionsUtils.ensureExtensionsInitialised(super.getClientExtensions());
        TlsSRPUtils.addSRPExtension(clientExtensions, this.identity);
        return clientExtensions;
    }

    public void processServerExtensions(Hashtable serverExtensions)
        throws IOException
    {
        if (!TlsUtils.hasExpectedEmptyExtensionData(serverExtensions, TlsSRPUtils.EXT_SRP,
            AlertDescription.illegal_parameter))
        {
            if (requireSRPServerExtension())
            {
                throw new TlsFatalAlert(AlertDescription.illegal_parameter);
            }
        }

        super.processServerExtensions(serverExtensions);
    }

    public TlsKeyExchange getKeyExchange()
        throws IOException
    {
        switch (selectedCipherSuite)
        {
        case CipherSuite.TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_WITH_AES_128_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_WITH_AES_256_CBC_SHA:
            return createSRPKeyExchange(KeyExchangeAlgorithm.SRP);

        case CipherSuite.TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA:
            return createSRPKeyExchange(KeyExchangeAlgorithm.SRP_RSA);

        case CipherSuite.TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA:
            return createSRPKeyExchange(KeyExchangeAlgorithm.SRP_DSS);

        default:
            /*
             * Note: internal error here; the TlsProtocol implementation verifies that the
             * server-selected cipher suite was in the list of client-offered cipher suites, so if
             * we now can't produce an implementation, we shouldn't have offered it!
             */
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }
    }

    public TlsAuthentication getAuthentication() throws IOException
    {
        /*
         * Note: This method is not called unless a server certificate is sent, which may be the
         * case e.g. for SRP_DSS or SRP_RSA key exchange.
         */
        throw new TlsFatalAlert(AlertDescription.internal_error);
    }

    public TlsCipher getCipher()
        throws IOException
    {
        switch (selectedCipherSuite)
        {
        case CipherSuite.TLS_SRP_SHA_WITH_3DES_EDE_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_RSA_WITH_3DES_EDE_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_DSS_WITH_3DES_EDE_CBC_SHA:
            return cipherFactory.createCipher(context, EncryptionAlgorithm._3DES_EDE_CBC, MACAlgorithm.hmac_sha1);

        case CipherSuite.TLS_SRP_SHA_WITH_AES_128_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_RSA_WITH_AES_128_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_DSS_WITH_AES_128_CBC_SHA:
            return cipherFactory.createCipher(context, EncryptionAlgorithm.AES_128_CBC, MACAlgorithm.hmac_sha1);

        case CipherSuite.TLS_SRP_SHA_WITH_AES_256_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_RSA_WITH_AES_256_CBC_SHA:
        case CipherSuite.TLS_SRP_SHA_DSS_WITH_AES_256_CBC_SHA:
            return cipherFactory.createCipher(context, EncryptionAlgorithm.AES_256_CBC, MACAlgorithm.hmac_sha1);

        default:
            /*
             * Note: internal error here; the TlsProtocol implementation verifies that the
             * server-selected cipher suite was in the list of client-offered cipher suites, so if
             * we now can't produce an implementation, we shouldn't have offered it!
             */
            throw new TlsFatalAlert(AlertDescription.internal_error);
        }
    }

    protected TlsKeyExchange createSRPKeyExchange(int keyExchange)
    {
        return new TlsSRPKeyExchange(keyExchange, supportedSignatureAlgorithms, groupVerifier, identity, password);
    }
}
