/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.apache.felix.jaas.internal;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Properties;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Service;
import org.osgi.framework.Bundle;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceReference;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServlet;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.io.PrintWriter;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static javax.security.auth.login.AppConfigurationEntry.LoginModuleControlFlag;
import static org.apache.felix.jaas.internal.BundleLoginModuleCreator.LoginModuleInfo;
import static org.apache.felix.jaas.internal.ConfigSpiOsgi.AppConfigurationHolder;

@Component
@Service(Servlet.class)
@Properties({ @Property(name = Constants.SERVICE_DESCRIPTION, value = "JAAS Web Console Plugin"),
        @Property(name = Constants.SERVICE_VENDOR, value = "The Apache Software Foundation"),
        @Property(name = "felix.webconsole.label", value = "jaas"),
        @Property(name = "felix.webconsole.title", value = "JAAS"),
        @Property(name = "felix.webconsole.configprinter.modes", value = "always") })
public class JaasWebConsolePlugin extends HttpServlet {

    /**
     * Static references to access internal data
     */
    private static ConfigSpiOsgi configSpi;

    private static BundleLoginModuleCreator loginModuleCreator;

    @Override
    protected void doGet(final HttpServletRequest req, final HttpServletResponse resp) throws ServletException, IOException {
        if (req.getPathInfo().endsWith("/data.json")) {
            getJson(resp);
        } else {
            getHtml(resp);
        }

    }

    private void getHtml(HttpServletResponse resp) throws IOException {
        final PrintWriter pw = resp.getWriter();

        printAppConfigurationDetails(pw);
        printAvailableModuleDetails(pw);

    }

    private void printAvailableModuleDetails(PrintWriter pw) {
        Map<Bundle,Set<String>> bundleMap = getAvailableLoginModuleInfo();

        pw.println("<p class=\"statline ui-state-highlight\">${Available LoginModules}</p>");
        if(bundleMap.isEmpty()){
            return;
        }

        pw.println("<table class=\"nicetable\">");
        pw.println("<thead><tr>");
        pw.println("<th class=\"header\">${Bundle}</th>");
        pw.println("<th class=\"header\">${Classes}</th>");
        pw.println("</tr></thead>");



        String rowClass = "odd";
        for(Map.Entry<Bundle,Set<String>> e : bundleMap.entrySet()){
            Bundle b = e.getKey();
            pw.print("<tr class=\"%s ui-state-default\">");
            pw.printf("<td><a href=\"${pluginRoot}/../bundles/%s\">%s (%s)</a></td>", b.getBundleId(),
                    b.getSymbolicName(), b.getBundleId());
            pw.printf("<td>");
            for(String className : e.getValue()){
                pw.print(className);
                pw.print("<br/>");
            }
            pw.print("</td>");
            pw.println("</tr>");

            if (rowClass.equals("odd")) {
                rowClass = "even";
            } else {
                rowClass = "odd";
            }
        }
        pw.println("</table>");
    }

    private void printAppConfigurationDetails(PrintWriter pw) {
        Map<String,List<AppConfigurationHolder>> configs =  getConfigurationDetails();
        if(configs.isEmpty()){
            pw.println("No JAAS LoginModule registered");
            return;
        }

        pw.println("<p class=\"statline ui-state-highlight\">${Registered LoginModules}</p>");

        pw.println("<table class=\"nicetable\">");
        pw.println("<thead><tr>");
        pw.println("<th class=\"header\">${Realm}</th>");
        pw.println("<th class=\"header\">${Rank}</th>");
        pw.println("<th class=\"header\">${Control Flag}</th>");
        pw.println("<th class=\"header\">${Type}</th>");
        pw.println("<th class=\"header\">${Classname}</th>");
        pw.println("</tr></thead>");

        for(Map.Entry<String,List<AppConfigurationHolder>> e : configs.entrySet()){
            String realm = e.getKey();
            pw.printf("<tr class=\"ui-state-default\"><td>%s</td><td colspan=\"4\"></td></tr>", realm);

            String rowClass = "odd";
            for(AppConfigurationHolder ah : e.getValue()){
                LoginModuleProvider lp = ah.getProvider();
                String type = getType(lp);
                pw.printf("<tr class=\"%s ui-state-default\"><td></td><td>%d</td>", rowClass,lp.ranking());
                pw.printf("<td>%s</td>",toString(lp.getControlFlag()));
                pw.printf("<td>%s</td>",type);

                pw.printf("<td>");
                pw.print(lp.getClassName());

                if(lp instanceof OsgiLoginModuleProvider){
                    ServiceReference sr = ((OsgiLoginModuleProvider) lp).getServiceReference();
                    Object id = sr.getProperty(Constants.SERVICE_ID);
                    pw.printf("<a href=\"${pluginRoot}/../services/%s\">(%s)</a>",id,id);
                }else if(lp instanceof ConfigLoginModuleProvider){
                    Map config = ((ConfigLoginModuleProvider) lp).getComponentConfig();
                    Object id = config.get(Constants.SERVICE_PID);
                    pw.printf("<a href=\"${pluginRoot}/../configMgr/%s\">(Details)</a>",id);
                }
                pw.printf("</td>");

                pw.println("</tr>");
                if (rowClass.equals("odd")) {
                    rowClass = "even";
                } else {
                    rowClass = "odd";
                }
            }
        }
        pw.println("</table>");
    }

    private String getType(LoginModuleProvider lp) {
        String type = "Service";
        if(lp instanceof ConfigLoginModuleProvider){
            type = "Configuration";
        }
        return type;
    }

    private void getJson(HttpServletResponse resp) {

    }

    /**
     * @see org.apache.felix.webconsole.ConfigurationPrinter#printConfiguration(java.io.PrintWriter)
     */
    @SuppressWarnings("UnusedDeclaration")
    public void printConfiguration(final PrintWriter pw) {
        pw.println("JAAS Configuration Details:");
        pw.println();
        pw.println("Registered LoginModules");
        Map<String,List<AppConfigurationHolder>> configs =  getConfigurationDetails();
        if(configs.isEmpty()){
            pw.println("No JAAS LoginModule registered");
        }else{
            for(Map.Entry<String,List<AppConfigurationHolder>> e : configs.entrySet()){
                String realm = e.getKey();
                pw.printf("Realm : %s \n",realm);
                for(AppConfigurationHolder ah : e.getValue()){
                    addSpace(pw,1);
                    pw.printf("%s \n", ah.getProvider().getClassName());

                    addSpace(pw, 2);pw.printf("Flag    : %s \n", toString(ah.getProvider().getControlFlag()));
                    addSpace(pw, 2);pw.printf("Type    : %s \n", getType(ah.getProvider()));
                    addSpace(pw, 2);pw.printf("Ranking : %d \n", ah.getProvider().ranking());
                }
            }
        }

        pw.println();

        Map<Bundle,Set<String>> bundleMap = getAvailableLoginModuleInfo();
        pw.println("Available LoginModules");
        if(bundleMap.isEmpty()){
            //Nothing to do
        }else{
            for(Map.Entry<Bundle,Set<String>> e : bundleMap.entrySet()){
                Bundle b = e.getKey();
                pw.printf("%s (%s) \n",b.getSymbolicName(), b.getBundleId());
                for(String className : e.getValue()){
                    addSpace(pw, 1);pw.println(className);
                }
            }
        }
    }

    private static void addSpace(PrintWriter pw, int count){
        for(int i = 0; i < count; i++){
            pw.print("  ");
        }
    }

    private static Map<String,List<AppConfigurationHolder>> getConfigurationDetails(){
        ConfigSpiOsgi spi = configSpi;
        if(spi == null){
            return Collections.emptyMap();
        }
        return  spi.getAllConfiguration();
    }

    private static Map<Bundle,Set<String>> getAvailableLoginModuleInfo(){
        BundleLoginModuleCreator lmc = loginModuleCreator;

        if(lmc == null){
            return Collections.emptyMap();
        }

        Map<String, LoginModuleInfo> infoMap = lmc.getLoginModuleInfo();
        Map<Bundle,Set<String>> bundleMap = new HashMap<Bundle, Set<String>>();

        //Determine the bundle -> login module classes map
        for(Map.Entry<String,LoginModuleInfo> e : infoMap.entrySet()){
            Bundle b = e.getValue().getBundle();
            Set<String> classNames = bundleMap.get(b);
            if(classNames == null){
                classNames = new HashSet<String>();
                bundleMap.put(b,classNames);
            }
            classNames.add(e.getKey());
        }
        return bundleMap;
    }

    private static String toString(LoginModuleControlFlag flag){
        if(flag == LoginModuleControlFlag.REQUIRED){
            return "REQUIRED";
        } else if(flag == LoginModuleControlFlag.REQUISITE){
            return "REQUISITE";
        } else if(flag == LoginModuleControlFlag.SUFFICIENT){
            return "SUFFICIENT";
        } else if(flag == LoginModuleControlFlag.OPTIONAL){
            return "OPTIONAL";
        }
        throw new IllegalArgumentException("Unknown flag "+flag);
    }

    static synchronized void setConfigSpi(ConfigSpiOsgi configSpi) {
        JaasWebConsolePlugin.configSpi = configSpi;
    }

    static synchronized void setLoginModuleCreator(BundleLoginModuleCreator loginModuleCreator) {
        JaasWebConsolePlugin.loginModuleCreator = loginModuleCreator;
    }

}
