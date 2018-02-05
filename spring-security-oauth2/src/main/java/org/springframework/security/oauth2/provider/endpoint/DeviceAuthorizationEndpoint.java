/*
 * Copyright 2006-2011 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 */


package org.springframework.security.oauth2.provider.endpoint;

import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.InsufficientAuthenticationException;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.common.OAuth2AccessToken;
import org.springframework.security.oauth2.common.exceptions.BadClientCredentialsException;
import org.springframework.security.oauth2.common.exceptions.InvalidClientException;
import org.springframework.security.oauth2.common.exceptions.OAuth2Exception;
import org.springframework.security.oauth2.common.util.OAuth2Utils;
import org.springframework.security.oauth2.provider.*;
import org.springframework.security.oauth2.provider.device.DeviceAuthorizationCodeServices;
import org.springframework.security.oauth2.provider.device.InMemoryDeviceAuthorizationCodeServices;
import org.springframework.security.oauth2.provider.request.DefaultOAuth2RequestValidator;
import org.springframework.util.StringUtils;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.ModelAndView;

import javax.servlet.http.HttpServletRequest;
import java.security.Principal;
import java.util.HashMap;
import java.util.Map;

/**
 * Endpoint for device flow: device authorize and user grant
 * https://tools.ietf.org/html/draft-ietf-oauth-device-flow-06
 *
 * @author Bin Wang
 */

@FrameworkEndpoint
public class DeviceAuthorizationEndpoint extends AbstractEndpoint {

    private DeviceAuthorizationCodeServices deviceAuthorizationCodeServices=new InMemoryDeviceAuthorizationCodeServices();
    private OAuth2RequestValidator oauth2RequestValidator = new DefaultOAuth2RequestValidator();
    private static int DEFAULT_INTERVAL=2;

    private static String PREFIX="device_";

    @RequestMapping(value = "/oauth/device_authorize",method = RequestMethod.POST)
    @ResponseBody
    public Map<String,?> deviceAuthorize(@RequestParam Map<String, String> parameters, Principal principal, HttpServletRequest request){

        if (!(principal instanceof Authentication) || !((Authentication) principal).isAuthenticated()) {
            throw new InsufficientAuthenticationException(
                    "User must be authenticated with Spring Security before authorization can be completed.");
        }else{
            if(StringUtils.isEmpty(parameters.get(OAuth2Utils.CLIENT_ID))) {
                parameters.put(OAuth2Utils.CLIENT_ID, ((Authentication) principal).getName());
            }else{
                if(!parameters.get(OAuth2Utils.CLIENT_ID).equals(getClientId(principal))){
                    throw new InvalidClientException("Given client ID does not match authenticated client");
                }
            }
        }

        AuthorizationRequest authorizationRequest = getOAuth2RequestFactory().createAuthorizationRequest(parameters);
        if (authorizationRequest.getClientId() == null) {
            throw new InvalidClientException("A client id must be provided");
        }



        ClientDetails client = getClientDetailsService().loadClientByClientId(authorizationRequest.getClientId());
        // We intentionally only validate the parameters requested by the client (ignoring any data that may have
        // been added to the request by the manager).
        oauth2RequestValidator.validateScope(authorizationRequest, client);

        String[] codes=deviceAuthorizationCodeServices.createAuthorizationCodes(authorizationRequest.createOAuth2Request());

        return buildDeviceResponse(codes[0],codes[1],client,request);
    }


    private Map<String,Object> buildDeviceResponse(String userCode,String deviceCode, ClientDetails client, HttpServletRequest request){
        Map response=new HashMap();
        response.put(OAuth2Utils.DEVICE_CODE,deviceCode);
        response.put(OAuth2Utils.USER_CODE,userCode);
        String verifyurl=null;
        try {
            verifyurl = client.getAdditionalInformation().get(PREFIX+OAuth2Utils.VERIFICATION_URI).toString();

        }catch (Exception ex){}
        if(StringUtils.isEmpty(verifyurl)) {
            StringBuffer url = request.getRequestURL();
            verifyurl= url.substring(0,url.lastIndexOf("/"))+"/user_verify";
        }
        response.put(OAuth2Utils.VERIFICATION_URI,verifyurl);
        Integer interval=null;
        try{
            interval=Integer.parseInt(client.getAdditionalInformation().get(PREFIX+OAuth2Utils.INTERVAL).toString());

        }catch (Exception ex) {

        }

        response.put(OAuth2Utils.INTERVAL,interval!=null?interval:DEFAULT_INTERVAL);

        response.put(OAuth2AccessToken.EXPIRES_IN,deviceAuthorizationCodeServices.getExpiresIn());
        return response;
    }
    /**
     * @param principal the currently authentication principal
     * @return a client id if there is one in the principal
     */
    protected String getClientId(Principal principal) {
        Authentication client = (Authentication) principal;
        if (!client.isAuthenticated()) {
            throw new InsufficientAuthenticationException("The client is not authenticated.");
        }
        String clientId = client.getName();
        if (client instanceof OAuth2Authentication) {
            // Might be a client and user combined authentication
            clientId = ((OAuth2Authentication) client).getOAuth2Request().getClientId();
        }
        return clientId;
    }
    @ExceptionHandler(NoSuchClientException.class)
    private ResponseEntity<OAuth2Exception> handleNoSuchClientException(NoSuchClientException e) throws  Exception{
        logger.info("Handling error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
        return getExceptionTranslator().translate(e);

    }
    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ResponseEntity<OAuth2Exception> handleHttpRequestMethodNotSupportedException(HttpRequestMethodNotSupportedException e) throws Exception {
        logger.info("Handling error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
        return getExceptionTranslator().translate(e);
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<OAuth2Exception> handleException(Exception e) throws Exception {
        logger.info("Handling error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
        return getExceptionTranslator().translate(e);
    }

    @ExceptionHandler(ClientRegistrationException.class)
    public ResponseEntity<OAuth2Exception> handleClientRegistrationException(Exception e) throws Exception {
        logger.info("Handling error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
        return getExceptionTranslator().translate(new BadClientCredentialsException());
    }

    @ExceptionHandler(OAuth2Exception.class)
    public ResponseEntity<OAuth2Exception> handleException(OAuth2Exception e) throws Exception {
        logger.info("Handling error: " + e.getClass().getSimpleName() + ", " + e.getMessage());
        return getExceptionTranslator().translate(e);
    }

    public void setDeviceAuthorizationCodeServices(DeviceAuthorizationCodeServices deviceAuthorizationCodeServices) {
        this.deviceAuthorizationCodeServices = deviceAuthorizationCodeServices;
    }

    public void setOauth2RequestValidator(OAuth2RequestValidator oauth2RequestValidator) {
        this.oauth2RequestValidator = oauth2RequestValidator;
    }
}
