package com.secunet.ipsmall;

import org.bouncycastle.crypto.params.DHParameters;

import com.secunet.bouncycastle.crypto.tls.AlertDescription;
import com.secunet.bouncycastle.crypto.tls.AlertLevel;
import com.secunet.bouncycastle.crypto.tls.Certificate;
import com.secunet.bouncycastle.crypto.tls.ProtocolVersion;
import com.secunet.bouncycastle.crypto.tls.SignatureAndHashAlgorithm;
import com.secunet.ipsmall.http.NanoHTTPD;
import com.secunet.ipsmall.log.IModuleLogger.ConformityResult;
import com.secunet.ipsmall.log.IModuleLogger.LogLevel;
import com.secunet.ipsmall.log.Logger;
import com.secunet.ipsmall.test.ITestData;
import com.secunet.ipsmall.test.ITestData.Type;
import com.secunet.ipsmall.tls.BouncyCastleNanoHTTPDSocketFactory;
import com.secunet.ipsmall.tls.BouncyCastleTlsHelper;
import com.secunet.ipsmall.tls.BouncyCastleTlsIcsMatcher;
import com.secunet.ipsmall.tls.BouncyCastleTlsNotificationListener;
import com.secunet.ipsmall.tobuilder.ics.TLSVersionType;

public class AttachedEIDServer extends NanoHTTPD implements BouncyCastleTlsNotificationListener {
    
    private BouncyCastleTlsIcsMatcher matcher;

    private boolean hasFatalErrors = false;
    
    private final EServiceWebServer eService;
    private final EIDServerWebServer eIDServer;
    
    public AttachedEIDServer(ITestData testData) {
        super(testData.getEServiceHost(), testData.getEServicePort(), null, testData.getEServiceTLSVersion(), testData.getEServiceTLSCipherSuites());
        this.logger = Logger.eService;
        this.testData = testData;
        
        // if we test without browser simulator, the first call to the eIDServer comes from a real browser, so we must skip the ICS check
        if(testData.getTestType() != null && testData.getTestType() == Type.BROWSER) {
            testData.setSkipNextICSCheck(true);
        }

        eService = new EServiceWebServer(testData, this);
        eIDServer = new EIDServerWebServer(testData, true, this);
        
        matcher = new BouncyCastleTlsIcsMatcher(IPSmallManager.getInstance().getIcs());

        try {
            BouncyCastleNanoHTTPDSocketFactory factory = new BouncyCastleNanoHTTPDSocketFactory(this, testData.getEServiceCertificate(), testData.getEServerPrivateKey());
            if(testData.getEServiceTLSdhParameters() != null && !testData.getEServiceTLSdhParameters().isEmpty()) {
                factory.setDHParameters(testData.getEServiceTLSdhParameters());
            }
            if(testData.getEServiceTLSecCurve() != null && !testData.getEServiceTLSecCurve().isEmpty()) {
                factory.setForcedCurveForECDHEKeyExchange(testData.getEServiceTLSecCurve());
            }
            if(testData.getEServiceTLSSignatureAlgorithm() != null && !testData.getEServiceTLSSignatureAlgorithm().isEmpty()) {
                factory.setForcedSignatureAlgorithm(testData.getEServiceTLSSignatureAlgorithm());
            }
            externalServerSocketFactory = factory;
        } catch (Exception e) {
            if (testData.useModifiedSSL()) {
                logger.logState("Error starting OpenSSL server: " + e.getMessage(), LogLevel.Error);
            } else {
                logger.logState("Error creating BC socket factory: " + e.getMessage(), LogLevel.Error);
            }
        }
        
    }
    
    @Override
    public Response serve(HTTPSession httpReq) {
        if(httpReq.getMethod() == Method.POST) {
            return eIDServer.serve(httpReq, logger);
        }
        else {
            return eService.serve(httpReq, logger);
        }
    }

    
    // BEGIN implementation of BouncyCastleTlsNotificationListener
    @Override
    public void notifyAlertRaised(short alertLevel, short alertDescription, String message, Throwable cause)
    {
        String logMessage = "TLS server raised alert: " + AlertLevel.getText(alertLevel) + ", " + AlertDescription.getText(alertDescription);
        if (message != null)
        {
            logMessage += " > " + message;
        }
        if (cause != null)
        {
            logMessage += " " + cause.toString();
        }
        logger.logState(logMessage);
        
        //TODO hack to suppress error TLS messages
        if(alertLevel == AlertLevel.fatal && alertDescription == AlertDescription.internal_error && "Failed to read record".equals(message) && cause instanceof java.io.EOFException) {
            doSuppressTLSErrors = true;
        }

        //close HTTP connections if TLS channel is closed
        if(alertLevel == AlertLevel.warning && alertDescription == AlertDescription.close_notify) {
            closeAllConnections();
        }
   }

    @Override
    public void notifyAlertReceived(short alertLevel, short alertDescription) {
        logger.logState("TLS server received alert: " + AlertLevel.getText(alertLevel) + ", "
                + AlertDescription.getText(alertDescription));
    }
    
    @Override
    public void notifyClientVersion(ProtocolVersion clientVersion) {
        logger.logState("TLS client offered version: " + clientVersion.toString());

        if(!testData.getSkipNextICSCheck()) {
            ProtocolVersion expectedProtocolVersion = BouncyCastleTlsHelper.convertProtocolVersionFromEnumToObject(testData.getEServiceTLSExpectedClientVersion());
            if( expectedProtocolVersion.equals(clientVersion) ) {
                logger.logConformity(ConformityResult.passed, "Check that client offered " + testData.getEServiceTLSExpectedClientVersion() + " passed.");
            }
            else {
                //TODO remove comment
                //hasFatalErrors = true;
                logger.logConformity(ConformityResult.failed, "Check that client offered " + testData.getEServiceTLSExpectedClientVersion() + " failed.");
            }
        }
    }

    @Override
    public void notifyFallback(boolean isFallback) {
        logger.logState("notifyFallback: " + isFallback);
    }

    @Override
    public void notifyOfferedCipherSuites(int[] offeredCipherSuites) {
        String cipherSuites = "";
        for(int cipherSuite : offeredCipherSuites) {
            cipherSuites += " " + BouncyCastleTlsHelper.convertCipherSuiteIntToString(cipherSuite);
        }
        logger.logState("TLS client offered cipher suites:" + cipherSuites);

        if(!testData.getSkipNextICSCheck()) {
            TLSVersionType expectedProtocolVersion = TLSVersionType.fromValue(testData.getEServiceTLSExpectedClientVersion());
            if( matcher.matchCipherSuites(true, expectedProtocolVersion, offeredCipherSuites) ) {
                logger.logConformity(ConformityResult.passed, "Check cipher suites against ICS passed.");
            }
            else {
                //TODO remove comment
                //hasFatalErrors = true;
                logger.logConformity(ConformityResult.failed, "Check cipher suites against ICS failed.");
            }
        }
    }

    @Override
    public void notifyOfferedCompressionMethods(short[] offeredCompressionMethods) {
        String methods = "";
        for(short method : offeredCompressionMethods) {
            methods += " " + method;
        }
        logger.logState("TLS client offered compression methods: [" + methods + " ]");
    }

    @Override
    public void notifyClientCertificate(Certificate clientCertificate) {
        logger.logState("notifyClientCertificate: " + clientCertificate);
    }

    @Override
    public void notifySecureRenegotiation(boolean secureRenegotiation) {
        logger.logState("notifySecureRenegotiation: " + secureRenegotiation);
    }

    @Override
    public void notifyHandshakeComplete() {
        logger.logState("TLS handshake complete!");
    }

    @Override
    public void notifyEncryptThenMACExtension(boolean hasEncryptThenMACExtension) {
        logger.logState("TLS client sent EncryptThenMAC extension: " + hasEncryptThenMACExtension);
    }

    @Override
    public void notifySignatureAlgorithmsExtension(SignatureAndHashAlgorithm[] signatureAlgorithms) {
        String algorithms = "";
        for (Object entry : signatureAlgorithms) {
            SignatureAndHashAlgorithm saha = (SignatureAndHashAlgorithm) entry;
            algorithms += " " + BouncyCastleTlsHelper.convertSignatureAndHashAlgorithmObjectToString(saha);
        }
        logger.logState("TLS client sent SignatureAlgorithms extension:" + algorithms);

        if(!testData.getSkipNextICSCheck()) {
            TLSVersionType expectedProtocolVersion = TLSVersionType.fromValue(testData.getEServiceTLSExpectedClientVersion());
            if( matcher.matchSignatureAndHashAlgorithms(true, expectedProtocolVersion, signatureAlgorithms) ) {
                logger.logConformity(ConformityResult.passed, "Check SignatureAlgorithms extension against ICS passed.");
            }
            else {
                //TODO remove comment
                //hasFatalErrors = true;
                logger.logConformity(ConformityResult.failed, "Check SignatureAlgorithms extension against ICS failed.");
            }
        }
    }

    @Override
    public void notifySupportedEllipticCurvesExtension(int[] namedCurves) {
        String curves = "";
        for (int entry : namedCurves) {
            curves += " " + BouncyCastleTlsHelper.convertNamedCurveIntToString(entry);
        }
        logger.logState("TLS client sent SupportedEllipticCurves extension:" + curves);

        if(!testData.getSkipNextICSCheck()) {
            TLSVersionType expectedProtocolVersion = TLSVersionType.fromValue(testData.getEServiceTLSExpectedClientVersion());
            if( matcher.matchEllipticCurves(true, expectedProtocolVersion, namedCurves) ) {
                logger.logConformity(ConformityResult.passed, "Check SupportedEllipticCurves extension against ICS passed.");
            }
            else {
                //TODO remove comment
                //hasFatalErrors = true;
                logger.logConformity(ConformityResult.failed, "Check SupportedEllipticCurves extension against ICS failed.");
            }
        }
    }

    @Override
    public void notifySupportedPointFormatsExtension(short[] supportedECPointFormats) {
        String formats = "";
        for (short format : supportedECPointFormats) {
            formats += " " + BouncyCastleTlsHelper.convertECPointFormatShortToString(format);
        }
        logger.logState("TLS client sent SupportedPointFormats extension:" + formats);
    }

    @Override
    public void notifySelectedVersion(ProtocolVersion clientVersion) {
        logger.logState("TLS server accepted version: " + clientVersion.toString());
    }

    @Override
    public void notifySelectedCipherSuite(int cipherSuite) {
        logger.logState("TLS server accepted cipher suite: " + BouncyCastleTlsHelper.convertCipherSuiteIntToString(cipherSuite));
    }

    @Override
    public void notifyEnabledCipherSuites(int[] enabledCipherSuites) {
        String cipherSuites = "";
        for(int cipherSuite : enabledCipherSuites) {
            cipherSuites += " " + BouncyCastleTlsHelper.convertCipherSuiteIntToString(cipherSuite);
        }
        logger.logState("TLS server enabled cipher suites:" + cipherSuites);
    }

    @Override
    public void notifyEnabledMinimumVersion(ProtocolVersion minimumVersion) {
        logger.logState("TLS server offers minimum version: " + minimumVersion.toString());
    }

    @Override
    public void notifyEnabledMaximumVersion(ProtocolVersion maximumVersion) {
        logger.logState("TLS server offers maximum version: " + maximumVersion.toString());
    }

    @Override
    public void notifySelectedDHParameters(DHParameters dhParameters) {
        logger.logState("TLS server selected DH parameters: " + BouncyCastleTlsHelper.convertDHParametersObjectToDHStandardGroupsString(dhParameters));
    }
    
    @Override
    public boolean hasFatalErrors() {
        return hasFatalErrors;
    }
    // END implementation of BouncyCastleTlsNotificationListener    
    
}
