package health.ere.ps.service.gematik;

import java.util.ArrayList;
import java.util.Date;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Level;
import java.util.logging.Logger;

import javax.enterprise.event.ObservesAsync;
import javax.mail.Authenticator;
import javax.mail.Message;
import javax.mail.Multipart;
import javax.mail.PasswordAuthentication;
import javax.mail.Session;
import javax.mail.Transport;
import javax.mail.internet.InternetAddress;
import javax.mail.internet.MimeBodyPart;
import javax.mail.internet.MimeMessage;
import javax.mail.internet.MimeMultipart;
import javax.naming.Context;
import javax.naming.NamingEnumeration;
import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.SearchControls;
import javax.naming.directory.SearchResult;
import javax.naming.ldap.InitialLdapContext;
import javax.naming.ldap.LdapContext;

import org.hl7.fhir.r4.model.Bundle;

import health.ere.ps.event.BundlesWithAccessCodeEvent;
import health.ere.ps.model.gematik.BundleWithAccessCodeOrThrowable;

public class KIMFlowtype169Service {

    private static Logger log = Logger.getLogger(KIMFlowtype169Service.class.getName());

    public void sendERezeptToKIMAddress(String fromKimAddress, String toKimAddress, String noteToPharmacy, String smtpHostServer, String smtpUser, String smtpPassword, String eRezeptToken) {
        try {
            Properties props = new Properties();

            props.put("mail.smtp.host", smtpHostServer);
            props.put("mail.smtp.auth", true);

            Session session = Session.getInstance(props, new Authenticator() {
                @Override
                protected PasswordAuthentication getPasswordAuthentication() {
                    return new PasswordAuthentication(smtpUser, smtpPassword);
                }
            });
            MimeMessage msg = new MimeMessage(session);
            //set message headers
            msg.addHeader("X-KIM-Dienstkennung", "eRezept;Zuweisung;V1.0");

            msg.setFrom(new InternetAddress(fromKimAddress));

            msg.setReplyTo(InternetAddress.parse(fromKimAddress, false));

            msg.setSubject("E-Rezept direkte Zuweisung", "UTF-8");


            MimeBodyPart textPart = new MimeBodyPart();
            textPart.setText(noteToPharmacy, "utf-8");

            MimeBodyPart erezeptTokenPart = new MimeBodyPart();
            erezeptTokenPart.setText(eRezeptToken, "utf8");
            
            Multipart multiPart = new MimeMultipart();
            multiPart.addBodyPart(textPart); // <-- first
            multiPart.addBodyPart(erezeptTokenPart); // <-- second
            msg.setContent(multiPart);

            msg.setSentDate(new Date());

            msg.setRecipients(Message.RecipientType.TO, InternetAddress.parse(toKimAddress, false));
            log.info("Message is ready");
            Transport.send(msg);  

            log.info("EMail Sent Successfully!!");
	    } catch (Exception e) {
	      log.log(Level.WARNING, "Error during sending E-Prescription", e);
	    }
    }

    public List<Map<String,Object>> search(String connectorIp, String searchDisplayName) {
    	List<Map<String,Object>> list = new ArrayList<>();
    	if(searchDisplayName == null || searchDisplayName.length() < 3) {
    		return list;
    	}
    	try {
            Hashtable<String, String> env = new Hashtable<>();
            env.put(Context.SECURITY_PROTOCOL, "ssl");
            env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
            env.put(Context.PROVIDER_URL, "ldaps://"+connectorIp+":636/");
            env.put(Context.SECURITY_AUTHENTICATION, "none");
            env.put("java.naming.ldap.factory.socket", "health.ere.ps.service.common.security.SSLSocketFactory");

            LdapContext ctx = new InitialLdapContext(env, null);
            ctx.setRequestControls(null);
            NamingEnumeration<?> namingEnum = ctx.search("dc=data,dc=vzd", "(&(professionOID=1.2.276.0.76.4.54)(|(displayName=*"+searchDisplayName+"*)(rfc822mailbox=*"+searchDisplayName+"*)))", getSimpleSearchControls());
            while (namingEnum.hasMore ()) {
                SearchResult result = (SearchResult) namingEnum.next();
                Attributes attrs = result.getAttributes();
                Map<String, Object> map = new HashMap<>();
                NamingEnumeration<? extends Attribute> enumeration = attrs.getAll();
                while(enumeration.hasMore()) {
                	Attribute attribute = enumeration.next();
                	map.put(attribute.getID(), attribute.get());
                }
                list.add(map);
            } 
            namingEnum.close();
            ctx.close();
        } catch (Exception e) {
            log.log(Level.WARNING, "Could not search LDAP", e);
            throw new RuntimeException(e);
        }
    	return list;
    }
    
    private SearchControls getSimpleSearchControls() {
        SearchControls searchControls = new SearchControls();
        searchControls.setSearchScope(SearchControls.SUBTREE_SCOPE);
        searchControls.setTimeLimit(30000);
        return searchControls;
    }

    public void onBundlesWithAccessCodeEvent(@ObservesAsync BundlesWithAccessCodeEvent bundlesWithAccessCodeEvent) {
        if("169".equals(bundlesWithAccessCodeEvent.getFlowtype())) {
            Map<String,String> kimConfigMap = bundlesWithAccessCodeEvent.getKimConfigMap();
            for(List<BundleWithAccessCodeOrThrowable> list : bundlesWithAccessCodeEvent.getBundleWithAccessCodeOrThrowable()) {
                for(BundleWithAccessCodeOrThrowable bundle : list) {
                    sendERezeptToKIMAddress(kimConfigMap.get("fromKimAddress"), bundlesWithAccessCodeEvent.getToKimAddress(), bundlesWithAccessCodeEvent.getNoteToPharmacy(), kimConfigMap.get("smtpHostServer"), getSmtpUser(kimConfigMap), kimConfigMap.get("smtpPassword"), getERezeptToken(bundle.getBundle(), bundle.getAccessCode()));
                }
            }
        }
    }

    private String getERezeptToken(Bundle bundle, String accessCode) {
        return "Task/"+bundle.getIdentifier().getValue()+"/$accept?ac="+accessCode;
    }

    private String getSmtpUser(Map<String, String> kimConfigMap) {
        return kimConfigMap.get("fromKimAddress")+"#"+kimConfigMap.get("smtpFdServer")+"#"+kimConfigMap.get("mandant-id")+"#"+kimConfigMap.get("client-system-id")+"#"+kimConfigMap.get("workplace-id");
    }
}
