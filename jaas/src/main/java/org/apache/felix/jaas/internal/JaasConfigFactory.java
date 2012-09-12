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

import org.apache.felix.jaas.LoginModuleFactory;
import org.apache.felix.scr.annotations.Activate;
import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Deactivate;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.PropertyOption;
import org.apache.felix.scr.annotations.PropertyUnbounded;
import org.apache.felix.scr.annotations.Reference;
import org.apache.sling.commons.osgi.PropertiesUtil;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.HashMap;
import java.util.Map;
import java.util.Properties;

@Component(
        label = "%jaas.name",
        description = "%jaas.description",
        configurationFactory = true,
        metatype = true,
        immediate = true,
        name = "org.apache.sling.auth.jaas.Configuration"
)
public class JaasConfigFactory {

    @Property
    private static final String JAAS_CLASS_NAME = "jaas.classname";


    @Property(value = "required",options = {
        @PropertyOption(name = "required",value = "%jaas.flag.required"),
        @PropertyOption(name = "requisite",value = "%jaas.flag.requisite"),
        @PropertyOption(name = "sufficient",value = "%jaas.flag.sufficient"),
        @PropertyOption(name = "optional",value = "%jaas.flag.optional")
    })
    private static final String JAAS_CONTROL_FLAG = "jaas.controlFlag";

    @Property(intValue = 0)
    private static final String JAAS_RANKING = "jaas.ranking";

    @Property(unbounded = PropertyUnbounded.ARRAY)
    private static final String JAAS_OPTIONS = "jaas.options";

    @Property
    private static final String JAAS_REALM_NAME = "jaas.realmName";

    private Logger log = LoggerFactory.getLogger(getClass());

    @Reference
    private LoginModuleCreator factory;

    private ServiceRegistration reg;

    @Activate
    private void activate(BundleContext context,Map config){
        String className = trimToNull(PropertiesUtil.toString(config.get(JAAS_CLASS_NAME),null));
        String flag = trimToNull(PropertiesUtil.toString(config.get(JAAS_CONTROL_FLAG), "required"));
        int ranking = PropertiesUtil.toInteger(config.get(JAAS_RANKING), 0);

        String[] props = PropertiesUtil.toStringArray(config.get(JAAS_OPTIONS), new String[0]);
        Map options = toMap(props);
        String realmName = trimToNull(PropertiesUtil.toString(config.get(JAAS_REALM_NAME),null));

        if(className == null){
           log.warn("Class name for the LoginModule is required. Configuration would be ignored"+config);
           return;
        }

        LoginModuleProvider lmf =
                new ConfigLoginModuleProvider(realmName,className,options,
                        ControlFlag.from(flag).flag(),ranking,factory, config);

        reg = context.registerService(LoginModuleFactory.class.getName(), lmf, new Properties());

    }

    @Deactivate
    private void deactivate(){
        if(reg != null){
            reg.unregister();
        }
    }


    //~----------------------------------- Utility Methods

    private static Map<String, Object> toMap(String[] props) {
        //TODO support system property substitution e.g. ${user.home}
        //in property values
        Map<String,Object> result = new HashMap<String, Object>();
        for(String kv : props){
            int indexOfEqual = kv.indexOf('=');
            if(indexOfEqual > 0){
                String key = trimToNull(kv.substring(0, indexOfEqual));
                String value = trimToNull(kv.substring(indexOfEqual + 1));
                if(key != null && value != null){
                    result.put(key,value);
                }
            }
        }
        return result;
    }

    //Instead of adding dependency on commons StringUtil we copy the used method below

    private static String trimToNull(String str) {
        String ts = trim(str);
        return isEmpty(ts) ? null : ts;
    }

    private static String trim(String str) {
        return str == null ? null : str.trim();
    }

    private static boolean isEmpty(String str) {
        return str == null || str.length() == 0;
    }

}
