/*
 * Copyright (c) 2009-2010 David Grant
 * Copyright (c) 2010 ThruPoint Ltd
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 */

package org.jscep.client;

import java.io.IOException;
import java.math.BigInteger;
import java.net.URL;
import java.security.InvalidKeyException;
import java.security.NoSuchAlgorithmException;
import java.security.NoSuchProviderException;
import java.security.PrivateKey;
import java.security.SignatureException;
import java.security.cert.CertStoreException;
import java.security.cert.CertificateException;
import java.security.cert.X509CRL;
import java.security.cert.X509Certificate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

import javax.security.auth.callback.Callback;
import javax.security.auth.callback.CallbackHandler;
import javax.security.auth.callback.UnsupportedCallbackException;

import org.bouncycastle.asn1.cms.IssuerAndSerialNumber;
import org.bouncycastle.asn1.pkcs.CertificationRequest;
import org.bouncycastle.asn1.x509.X509Name;
import org.jscep.CertificateVerificationCallback;
import org.jscep.content.CaCapabilitiesContentHandler;
import org.jscep.content.CaCertificateContentHandler;
import org.jscep.content.NextCaCertificateContentHandler;
import org.jscep.message.PkcsPkiEnvelopeDecoder;
import org.jscep.message.PkcsPkiEnvelopeEncoder;
import org.jscep.message.PkiMessageDecoder;
import org.jscep.message.PkiMessageEncoder;
import org.jscep.request.GetCaCaps;
import org.jscep.request.GetCaCert;
import org.jscep.request.GetNextCaCert;
import org.jscep.response.Capabilities;
import org.jscep.transaction.EnrolmentTransaction;
import org.jscep.transaction.MessageType;
import org.jscep.transaction.NonEnrollmentTransaction;
import org.jscep.transaction.OperationFailureException;
import org.jscep.transaction.Transaction;
import org.jscep.transaction.Transaction.State;
import org.jscep.transport.Transport;
import org.jscep.util.LoggingUtil;

/**
 * This class represents a SCEP client, or Requester.
 */
public class Client {
	private static Logger LOGGER = LoggingUtil.getLogger(Client.class);
	private Map<String, Capabilities> capabilitiesCache = new HashMap<String, Capabilities>();
	private Set<X509Certificate> verified = new HashSet<X509Certificate>(1);
	private String preferredDigestAlg;
	private String preferredCipherAlg;
	
	// A requester MUST have the following information locally configured:
	//
    // 1.  The Certification Authority IP address or fully qualified domain name
    // 2.  The Certification Authority HTTP CGI script path
	//
	// We use a URL for this.
    private final URL url;
    // Before a requester can start a PKI transaction, it MUST have at least
    // one RSA key pair use for signing the SCEP pkiMessage (Section 3.1).
    //
    // The following identity and key pair is used for this case.
    private final X509Certificate identity;
    private final PrivateKey priKey;
    // A requester MUST have the following information locally configured:
    //
    // 3. The identifying information that is used for authentication of the 
    // Certification Authority in Section 4.1.1.  This information MAY be 
    // obtained from the user, or presented to the end user for manual 
    // authorization during the protocol exchange (e.g. the user indicates 
    // acceptance of a fingerprint via a user-interface element).
    //
    // We use a callback handler for this.
    private final CallbackHandler cbh;
    // The requester MUST have MESSAGE information configured if the
    // Certification Authority requires it (see Section 5.1).
    //
    // How does one determine that the CA _requires_ this?
    private final String profile;
    
    /**
     * Creates a new Client instance without a profile identifier.
     * <p>
     * This method will throw a NullPointerException if any of the arguments are null,
     * and an InvalidArgumentException if any of the arguments is invalid.
     * 
     * @param url the URL to the SCEP server.
     * @param client the certificate to identify this client.
     * @param priKey the private key for the identity.
     * @param cbh the callback handler to check the CA identity.
     */
    public Client(URL url, X509Certificate client, PrivateKey priKey, CallbackHandler cbh) {
    	this(url, client, priKey, cbh, null);
    }
    
    /**
     * Creates a new Client instance with a profile identifier.
     * <p>
     * With the exception of the profile name, this method will throw a 
     * NullPointerException if any of the arguments are null, and an 
     * InvalidArgumentException if any of the arguments is invalid.
     * 
     * @param url the URL to the SCEP server.
     * @param client the certificate to identify this client.
     * @param priKey the private key for the identity.
     * @param cbh the callback handler to check the CA identity.
     * @param profile the name of the CA profile.
     */
    public Client(URL url, X509Certificate client, PrivateKey priKey, CallbackHandler cbh, String profile) {
    	this.url = url;
    	this.identity = client;
    	this.priKey = priKey;
    	this.cbh = cbh;
    	this.profile = profile;
    	
    	validateInput();
    }
    
    // INFORMATIONAL REQUESTS
    
    /**
     * Retrieves the set of SCEP capabilities from the CA.
     * 
     * @return the capabilities of the server.
     * @throws IOException if any I/O error occurs.
     */
    public Capabilities getCaCapabilities() throws IOException {
    	// NON-TRANSACTIONAL
    	return getCaCapabilities(false);
    }
    
    /**
     * Retrieves the CA certificate.
     * <p>
     * If the CA is using an RA, the RA certificate will also
     * be present in the returned list.
     * 
     * @return the list of certificates.
     * @throws IOException if any I/O error occurs.
     */
    public List<X509Certificate> getCaCertificate() throws IOException {
    	// NON-TRANSACTIONAL
    	// CA and RA public key distribution
    	LOGGER.entering(getClass().getName(), "getCaCertificate");
    	final GetCaCert req = new GetCaCert(profile, new CaCertificateContentHandler());
        final Transport trans = Transport.createTransport(Transport.Method.GET, url);
        
        final List<X509Certificate> certs = trans.sendRequest(req);
        verifyCA(selectCA(certs));
        
        LOGGER.exiting(getClass().getName(), "getCaCertificate", certs);
        return certs;
    }
    
    /**
     * Retrieves the "rollover" certificate to be used by the CA.
     * <p>
     * If the CA is using an RA, the RA certificate will be present
     * in the returned list.
     * 
     * @return the list of certificates.
     * @throws IOException if any I/O error occurs.
     */
    public List<X509Certificate> getRolloverCertificate() throws IOException {
    	// NON-TRANSACTIONAL
    	if (getCaCapabilities().isRolloverSupported() == false) {
    		throw new UnsupportedOperationException();
    	}
    	final X509Certificate issuer = getRecipientCertificate();
    	
    	final Transport trans = Transport.createTransport(Transport.Method.GET, url);
    	final GetNextCaCert req = new GetNextCaCert(profile, new NextCaCertificateContentHandler(issuer));
    	
    	return trans.sendRequest(req);
    }
    
    // TRANSACTIONAL
    
    /**
     * Returns the current CA's certificate revocation list
     *  
     * @return a collection of CRLs
     * @throws IOException if any I/O error occurs.
     * @throws PkiOperationFailureException if the operation fails.
     */
    @SuppressWarnings("unchecked")
	public X509CRL getRevocationList() throws IOException, OperationFailureException {
    	// TRANSACTIONAL
    	// CRL query
    	final X509Certificate ca = retrieveCA();
    	if (supportsDistributionPoints(ca)) {
    		throw new RuntimeException("Unimplemented");
    	}
    	
    	X509Name name = new X509Name(ca.getIssuerX500Principal().toString());
    	BigInteger serialNumber = ca.getSerialNumber();
    	IssuerAndSerialNumber iasn = new IssuerAndSerialNumber(name, serialNumber);
    	Transport transport = createTransport();
    	final Transaction t = new NonEnrollmentTransaction(transport, getEncoder(), getDecoder(), iasn, MessageType.GetCRL);
    	t.send();
    	
    	if (t.getState() == State.CERT_ISSUED) {
			try {
				Collection<X509CRL> crls = (Collection<X509CRL>) t.getCertStore().getCRLs(null);
				if (crls.size() == 0) {
					return null;
				}
				return crls.iterator().next();
			} catch (CertStoreException e) {
				throw new RuntimeException(e);
			}
		} else if (t.getState() == State.CERT_REQ_PENDING) {
			throw new IllegalStateException();
		} else {
			throw new OperationFailureException(t.getFailInfo());
		}
    }
    
    /**
     * Returns the certificate corresponding to the provided serial number, as issued
     * by the current CA.
     * 
     * @param serial the serial number.
     * @return the certificate.
     * @throws IOException if any I/O error occurs.
     * @throws PkiOperationFailureException if the operation fails.
     */
    @SuppressWarnings("unchecked")
	public List<X509Certificate> getCertificate(BigInteger serial) throws IOException, OperationFailureException {
    	// TRANSACTIONAL
    	// Certificate query
    	final X509Certificate ca = retrieveCA();;
    	
    	X509Name name = new X509Name(ca.getIssuerX500Principal().toString());
    	BigInteger serialNumber = ca.getSerialNumber();
    	IssuerAndSerialNumber iasn = new IssuerAndSerialNumber(name, serialNumber);
    	Transport transport = createTransport();
    	final Transaction t = new NonEnrollmentTransaction(transport, getEncoder(), getDecoder(), iasn, MessageType.GetCert);
		t.send();
    	
		if (t.getState() == State.CERT_ISSUED) {
			try {
				Collection<X509Certificate> certs = (Collection<X509Certificate>) t.getCertStore().getCertificates(null);
				return new ArrayList<X509Certificate>(certs);
			} catch (CertStoreException e) {
				throw new RuntimeException(e);
			}
		} else if (t.getState() == State.CERT_REQ_PENDING) {
			throw new IllegalStateException();
		} else {
			throw new OperationFailureException(t.getFailInfo());
		}
    }
    
    
    /**
     * Enrolls the provided CSR into a PKI.
     * 
     * @param csr the certificate signing request
     * @return the enrollment transaction.
     * @throws IOException if any I/O error occurs.
     */
    public EnrolmentTransaction enrol(CertificationRequest csr) throws IOException {
    	// TRANSACTIONAL
    	// Certificate enrollment
    	final Transport transport = createTransport();
    	List<X509Certificate> cas = getCaCertificate();
    	X509Certificate encoderCa = selectRecipient(cas);
    	X509Certificate ca = selectCA(cas);
    	PkcsPkiEnvelopeEncoder envEncoder = new PkcsPkiEnvelopeEncoder(encoderCa);
    	PkiMessageEncoder encoder = new PkiMessageEncoder(priKey, identity, envEncoder);
    	
    	
    	final EnrolmentTransaction t = new EnrolmentTransaction(transport, encoder, getDecoder(), csr);
    	t.setIssuer(ca);
    	
    	return t;
    }
    
    /**
     * Validates all the input to this client.
     * 
     * @throws NullPointerException if any member variables are null.
     * @throws IllegalArgumentException if any member variables are invalid.
     */
    private void validateInput() throws NullPointerException, IllegalArgumentException {
    	// Check for null values first.
    	if (url == null) {
    		throw new NullPointerException("URL should not be null");
    	}
    	if (identity == null) {
    		throw new NullPointerException("Identity should not be null");
    	}
    	if (priKey == null) {
    		throw new NullPointerException("Private key should not be null");
    	}
    	if (cbh == null) {
    		throw new NullPointerException("Callback handler should not be null");
    	}
    	
    	if (identity.getPublicKey().getAlgorithm().equals("RSA") == false) {
    		throw new IllegalArgumentException("Public key algorithm should be RSA");
    	}
    	if (priKey.getAlgorithm().equals("RSA") == false) {
    		throw new IllegalArgumentException("Private key algorithm should be RSA");
    	}
    	if (url.getProtocol().matches("^https?$") == false) {
    		throw new IllegalArgumentException("URL protocol should be HTTP or HTTPS");
    	}
    	if (url.getRef() != null) {
    		throw new IllegalArgumentException("URL should contain no reference");
    	}
    	if (url.getQuery() != null) {
    		throw new IllegalArgumentException("URL should contain no query string");
    	}
    }
    
    private PkiMessageEncoder getEncoder() throws IOException {
    	PkcsPkiEnvelopeEncoder envEncoder = new PkcsPkiEnvelopeEncoder(getRecipientCertificate());
    	
		return new PkiMessageEncoder(priKey, identity, envEncoder);
    }
    
    private PkiMessageDecoder getDecoder() {
    	PkcsPkiEnvelopeDecoder envDecoder = new PkcsPkiEnvelopeDecoder(priKey);
    	
		return new PkiMessageDecoder(envDecoder);
    }
    
    /**
     * @link http://tools.ietf.org/html/draft-nourse-scep-19#section-2.2.4
     */
    private boolean supportsDistributionPoints(X509Certificate issuerCertificate) {
    	return issuerCertificate.getExtensionValue("2.5.29.31") != null;
    }
    
    /**
     * Creates a new transport based on the capabilities of the server.
     * 
     * @return the new transport.
     * @throws IOException if any I/O error occurs.
     */
    private Transport createTransport() throws IOException {
    	LOGGER.entering(getClass().getName(), "createTransport");
    	
    	final Transport t;
    	if (getCaCapabilities(true).isPostSupported()) {
    		t = Transport.createTransport(Transport.Method.POST, url);
    	} else {
    		t = Transport.createTransport(Transport.Method.GET, url);
    	}
    	
    	LOGGER.exiting(getClass().getName(), "createTransport", t);
    	
    	return t;
    }
    
    private Capabilities getCaCapabilities(boolean useCache) throws IOException {
    	// NON-TRANSACTIONAL
    	LOGGER.entering(getClass().getName(), "getCaCapabilities", useCache);
    	
    	Capabilities caps = null;
    	if (useCache == true) {
    		caps = capabilitiesCache.get(profile);
    	}
    	if (caps == null) {
	    	final GetCaCaps req = new GetCaCaps(profile, new CaCapabilitiesContentHandler());
	        final Transport trans = Transport.createTransport(Transport.Method.GET, url);
	        caps = trans.sendRequest(req);
	        capabilitiesCache.put(profile, caps);
    	}
        
        LOGGER.exiting(getClass().getName(), "getCaCapabilities", caps);
        return caps;
    }
    
    private void verifyCA(X509Certificate cert) throws IOException {
    	// Cache
    	if (verified.contains(cert)) {
    		LOGGER.finer("Verification Cache Hit.");
    		return;
    	} else {
    		LOGGER.finer("Verification Cache Missed.");
    	}

		CertificateVerificationCallback callback = new CertificateVerificationCallback(cert);
		try {
			cbh.handle(new Callback[] {callback});
		} catch (UnsupportedCallbackException e) {
			throw new RuntimeException(e);
		}
		if (callback.isVerified() == false) {
			throw new IOException("CA certificate fingerprint could not be verified.");
		} else {
			verified.add(cert);
		}
    }
    
    private X509Certificate retrieveCA() throws IOException {
    	return selectCA(getCaCertificate());
    }
    
    private X509Certificate getRecipientCertificate() throws IOException {
    	final List<X509Certificate> certs = getCaCertificate();
    	// The CA or RA
    	return selectRecipient(certs);
    }
    
    private X509Certificate selectRecipient(List<X509Certificate> chain) {
    	int numCerts = chain.size();
    	if (numCerts == 2) {
    		final X509Certificate ca = selectCA(chain);
    		// The RA certificate is the other one.
    		int caIndex = chain.indexOf(ca);
    		int raIndex = 1 - caIndex;
    		
    		return chain.get(raIndex);
    	} else if (numCerts == 1) {
    		return chain.get(0);
    	} else if (numCerts == 3) {
    		// Entrust Case, we have CA certificate and two RA 
    		// certificates (Encryption and Verification) we use the 
    		// RA Encryption certificate as recipient
    		int raIndex = 1;
    		for (int i = 0; i < chain.size(); i++) {
    			X509Certificate raCert = chain.get(i);
    			if (raCert.getKeyUsage()[0] == false && raCert.getKeyUsage()[6] == false) {
    				//this must be the Encryption certificate because 
    				// no digitalSignature and no CRL sign extension is set
    				raIndex = i;
    			}
    		}
    		return chain.get(raIndex);
    	} else {
    		// We've either got NO certificates here, or more than 2.
    		// Whatever the case, the server is in error. 
    		throw new IllegalStateException();
    	}
    }
    
    private X509Certificate selectCA(List<X509Certificate> certs) {
    	if (certs.size() == 1) {
    		// Only one certificate, so it must be the CA.
    		return certs.get(0);
    	}
    	// We don't know the order in the chain, but we know the RA 
    	// certificate MUST have been issued by the CA certificate.
    	for (int i = 0; i < certs.size(); i++) {
    		// Hypothetical CA
    		X509Certificate ca = certs.get(i);
    		for (int j = 0; j < certs.size(); j++) {
    			// Hypothetical RA
    			X509Certificate ra = certs.get(j);
    			try {
    				// If the hypothetical RA is signed by the
    				// hypothetical CA, the CA is legitimate.
					ra.verify(ca.getPublicKey());
					
					// No exception, so return.
					return ca;
				} catch (Exception e) {
					// Problem verifying, move on.
				}
    		}
    	}
    	throw new IllegalStateException("No CA in chain");
    }
    
    void setPreferredCipherAlgorithm(String algorithm) {
    	preferredCipherAlg = algorithm;
    }
    
    void setPreferredDigestAlgorithm(String algorithm) {
    	preferredDigestAlg = algorithm;
    }
}
