package com.fluxtream.connectors.flickr;


import static com.fluxtream.utils.HttpUtils.fetch;
import static com.fluxtream.utils.Utils.hash;

import java.io.IOException;
import java.io.StringReader;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import nu.xom.Element;

import org.dom4j.Document;
import org.dom4j.DocumentException;
import org.dom4j.io.SAXReader;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Controller;
import org.springframework.web.bind.annotation.RequestMapping;

import com.fluxtream.Configuration;
import com.fluxtream.connectors.Connector;
import com.fluxtream.domain.Guest;
import com.fluxtream.mvc.controllers.ControllerHelper;
import com.fluxtream.services.GuestService;

@Controller
@RequestMapping(value="/flickr")
public class FlickrController {

	@Autowired
	GuestService guestService;
	
	@Autowired
	Configuration env;
	
	@RequestMapping(value = "/token")
	public String getToken(HttpServletRequest request,
			HttpServletResponse response)
		throws NoSuchAlgorithmException
	{
		String api_key = env.get("flickrConsumerKey");
		String api_sig = env.get("flickrConsumerSecret") + "api_key" + api_key + "permsread";
		api_sig = hash(api_sig);
		String loginUrl = "http://flickr.com/services/auth/" +
			"?api_key=" + api_key + "&perms=read&api_sig=" + api_sig;
		return "redirect:" + loginUrl;
	}
	
	@RequestMapping(value = "/upgradeToken")
	public String upgradeToken(HttpServletRequest request,
			HttpServletResponse response) throws NoSuchAlgorithmException, IOException, DocumentException {
		String api_key = env.get("flickrConsumerKey");
		String frob = request.getParameter("frob");
		
		Map<String,String> params = new HashMap<String,String>();
		params.put("method", "flickr.auth.getToken");
		params.put("api_key", api_key);
		params.put("frob", frob);
		
		String api_sig = sign(params);
		
		String getTokenUrl = "http://api.flickr.com/services/rest/" +
			"?method=flickr.auth.getToken&api_key=" + api_key + "&frob=" + frob + "&api_sig=" + api_sig;

		Guest guest = ControllerHelper.getGuest();
		long guestId = guest.getId();
		
		String authToken = fetch(getTokenUrl, env);
		
        SAXReader reader = new SAXReader();
        Document document = reader.read(new StringReader(authToken));

		Element user = (Element) document.selectSingleNode("auth/user");
		
		String username = user.getAttributeValue("username");
		username = username.substring(1, username.length()-1);
		
		String nsid = user.getAttributeValue("nsid");
		nsid = nsid.substring(1, nsid.length()-1);
		
		String fullname = user.getAttributeValue("fullname");
		fullname = fullname.substring(1, fullname.length()-1);
		
		String token = document.selectSingleNode("auth/token/text()").getStringValue();
		
		Connector flickrConnector = Connector.getConnector("flickr");
		
		guestService.setApiKeyAttribute(guestId, flickrConnector, "username", username);
		guestService.setApiKeyAttribute(guestId, flickrConnector, "token", token);
		guestService.setApiKeyAttribute(guestId, flickrConnector, "nsid", nsid);
		guestService.setApiKeyAttribute(guestId, flickrConnector, "fullname", fullname);
		
		return "redirect://from/"+flickrConnector.getName();
	}
	
	String sign(Map<String,String> parameters) throws NoSuchAlgorithmException {
		String toSign = env.get("flickrConsumerSecret");
	    SortedSet<String> eachKey= new TreeSet<String>(parameters.keySet());
		for (String key : eachKey)
			toSign += key + parameters.get(key);
		String sig = hash(toSign);
		return sig;
	}
	
	String getConsumerKey() {
		return env.get("flickrConsumerKey");
	}

	String getConsumerSecret() {
		return env.get("flickrConsumerSecret");
	}
	
}
