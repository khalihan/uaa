/*
 * *****************************************************************************
 *      Cloud Foundry
 *      Copyright (c) [2009-2015] Pivotal Software, Inc. All Rights Reserved.
 *      This product is licensed to you under the Apache License, Version 2.0 (the "License").
 *      You may not use this product except in compliance with the License.
 *
 *      This product includes a number of subcomponents with
 *      separate copyright notices and license terms. Your use of these
 *      subcomponents is subject to the terms and conditions of the
 *      subcomponent's license, as noted in the LICENSE file.
 * *****************************************************************************
 */

package org.cloudfoundry.identity.uaa.login.saml;

import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.cloudfoundry.identity.uaa.authentication.Origin;
import org.cloudfoundry.identity.uaa.util.JsonUtils;
import org.cloudfoundry.identity.uaa.zone.IdentityProvider;
import org.cloudfoundry.identity.uaa.zone.IdentityProviderProvisioning;
import org.cloudfoundry.identity.uaa.zone.IdentityZone;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneHolder;
import org.cloudfoundry.identity.uaa.zone.IdentityZoneProvisioning;
import org.opensaml.saml2.metadata.EntitiesDescriptor;
import org.opensaml.saml2.metadata.EntityDescriptor;
import org.opensaml.saml2.metadata.RoleDescriptor;
import org.opensaml.saml2.metadata.provider.MetadataFilter;
import org.opensaml.saml2.metadata.provider.MetadataProvider;
import org.opensaml.saml2.metadata.provider.MetadataProviderException;
import org.opensaml.saml2.metadata.provider.ObservableMetadataProvider;
import org.opensaml.xml.XMLObject;
import org.opensaml.xml.security.x509.PKIXValidationInformationResolver;
import org.opensaml.xml.signature.SignatureTrustEngine;
import org.springframework.beans.factory.BeanNameAware;
import org.springframework.beans.factory.DisposableBean;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.security.saml.key.KeyManager;
import org.springframework.security.saml.metadata.CachingMetadataManager;
import org.springframework.security.saml.metadata.ExtendedMetadata;
import org.springframework.security.saml.metadata.ExtendedMetadataDelegate;
import org.springframework.security.saml.metadata.ExtendedMetadataProvider;
import org.springframework.security.saml.metadata.MetadataManager;
import org.springframework.security.saml.trust.httpclient.TLSProtocolConfigurer;

import javax.annotation.PostConstruct;
import javax.xml.namespace.QName;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Timer;
import java.util.TimerTask;
import java.util.concurrent.ConcurrentHashMap;

public class ZoneAwareMetadataManager extends MetadataManager implements ExtendedMetadataProvider, InitializingBean, DisposableBean, BeanNameAware {

    private static final Log logger = LogFactory.getLog(ZoneAwareMetadataManager.class);
    private IdentityProviderProvisioning providerDao;
    private IdentityZoneProvisioning zoneDao;
    private IdentityProviderConfigurator configurator;
    private KeyManager keyManager;
    private Map<IdentityZone,ExtensionMetadataManager> metadataManagers;
    private long refreshInterval = 30000l;
    private long lastRefresh = 0;
    private Timer timer;
    private String beanName = ZoneAwareMetadataManager.class.getName()+"-"+System.identityHashCode(this);
    private ProviderChangedListener providerChangedListener;

    public ZoneAwareMetadataManager(IdentityProviderProvisioning providerDao,
                                    IdentityZoneProvisioning zoneDao,
                                    IdentityProviderConfigurator configurator,
                                    KeyManager keyManager,
                                    ProviderChangedListener listener) throws MetadataProviderException {
        super(Collections.<MetadataProvider>emptyList());
        this.providerDao = providerDao;
        this.zoneDao = zoneDao;
        this.configurator = configurator;
        this.keyManager = keyManager;
        super.setKeyManager(keyManager);
        //disable internal timer
        super.setRefreshCheckInterval(0);
        if (metadataManagers==null) {
            metadataManagers = new ConcurrentHashMap<>();
        }
        providerChangedListener = listener;
    }

    private class RefreshTask extends TimerTask {
        @Override
        public void run() {
            try {
                refreshAllProviders();
            }catch (Exception x) {
                log.error("Unable to run refresh task:", x);
            }
        }
    }

    @Override
    public void setBeanName(String name) {
        this.beanName = name;
    }

    @PostConstruct
    public void checkAllProviders() throws MetadataProviderException {
        for (Map.Entry<IdentityZone,ExtensionMetadataManager> entry : metadataManagers.entrySet()) {
            entry.getValue().setKeyManager(keyManager);
        }
        refreshAllProviders();
        timer = new Timer("ZoneAwareMetadataManager.Refresh["+beanName+"]", true);
        timer.schedule(new RefreshTask(),refreshInterval , refreshInterval);
        providerChangedListener.setMetadataManager(this);
    }

    protected void refreshAllProviders() throws MetadataProviderException {
        for (IdentityZone zone : zoneDao.retrieveAll()) {
            ExtensionMetadataManager manager = getManager(zone);
            boolean hasChanges = false;
            for (IdentityProvider provider : providerDao.retrieveAll(zone.getId())) {
                if (Origin.SAML.equals(provider.getType()) && lastRefresh < provider.getLastModified().getTime()) {
                    try {
                        IdentityProviderDefinition definition = JsonUtils.readValue(provider.getConfig(), IdentityProviderDefinition.class);
                        manager.addMetadataProvider(configurator.addIdentityProviderDefinition(definition));
                        hasChanges = true;
                    } catch (JsonUtils.JsonUtilException x) {
                        logger.error("Unable to load provider:"+provider, x);
                    }
                }
            }
            if (hasChanges) {
                refreshZoneManager(manager);
            }
        }
        lastRefresh = System.currentTimeMillis();
    }

    protected ExtensionMetadataManager getManager(IdentityZone zone) {
        if (metadataManagers==null) { //called during super constructor
            metadataManagers = new ConcurrentHashMap<>();
        }
        ExtensionMetadataManager manager = metadataManagers.get(zone);
        if (manager==null) {
            try {
                manager = new ExtensionMetadataManager(Collections.<MetadataProvider>emptyList());
            } catch (MetadataProviderException e) {
                throw new IllegalStateException(e);
            }
            manager.setKeyManager(keyManager);
            ((ConcurrentHashMap)metadataManagers).putIfAbsent(zone, manager);
        }
        return metadataManagers.get(zone);
    }
    protected ExtensionMetadataManager getManager() {
        return getManager(IdentityZoneHolder.get());
    }

    @Override
    public void setProviders(List<MetadataProvider> newProviders) throws MetadataProviderException {
        getManager().setProviders(newProviders);
    }

    @Override
    public void refreshMetadata() {
        getManager().refreshMetadata();
    }

    @Override
    public void addMetadataProvider(MetadataProvider newProvider) throws MetadataProviderException {
        getManager().addMetadataProvider(newProvider);
    }

    @Override
    public void removeMetadataProvider(MetadataProvider provider) {
        getManager().removeMetadataProvider(provider);
    }

    @Override
    public List<MetadataProvider> getProviders() {
        return getManager().getProviders();
    }

    @Override
    public List<ExtendedMetadataDelegate> getAvailableProviders() {
        return getManager().getAvailableProviders();
    }

    @Override
    protected void initializeProvider(ExtendedMetadataDelegate provider) throws MetadataProviderException {
        getManager().initializeProvider(provider);
    }

    @Override
    protected void initializeProviderData(ExtendedMetadataDelegate provider) throws MetadataProviderException {
        getManager().initializeProviderData(provider);
    }

    @Override
    protected void initializeProviderFilters(ExtendedMetadataDelegate provider) throws MetadataProviderException {
        getManager().initializeProviderFilters(provider);
    }

    @Override
    protected SignatureTrustEngine getTrustEngine(MetadataProvider provider) {
        return getManager().getTrustEngine(provider);
    }

    @Override
    protected PKIXValidationInformationResolver getPKIXResolver(MetadataProvider provider, Set<String> trustedKeys, Set<String> trustedNames) {
        return getManager().getPKIXResolver(provider, trustedKeys, trustedNames);
    }

    @Override
    protected List<String> parseProvider(MetadataProvider provider) throws MetadataProviderException {
        return getManager().parseProvider(provider);
    }

    @Override
    public Set<String> getIDPEntityNames() {
        return getManager().getIDPEntityNames();
    }

    @Override
    public Set<String> getSPEntityNames() {
        return getManager().getSPEntityNames();
    }

    @Override
    public boolean isIDPValid(String idpID) {
        return getManager().isIDPValid(idpID);
    }

    @Override
    public boolean isSPValid(String spID) {
        return getManager().isSPValid(spID);
    }

    @Override
    public String getHostedSPName() {
        return getManager().getHostedSPName();
    }

    @Override
    public void setHostedSPName(String hostedSPName) {
        getManager().setHostedSPName(hostedSPName);
    }

    @Override
    public String getDefaultIDP() throws MetadataProviderException {
        return getManager().getDefaultIDP();
    }

    @Override
    public void setDefaultIDP(String defaultIDP) {
        getManager().setDefaultIDP(defaultIDP);
    }

    @Override
    public EntityDescriptor getEntityDescriptor(byte[] hash) throws MetadataProviderException {
        return getManager().getEntityDescriptor(hash);
    }

    @Override
    public String getEntityIdForAlias(String entityAlias) throws MetadataProviderException {
        return getManager().getEntityIdForAlias(entityAlias);
    }

    @Override
    public ExtendedMetadata getDefaultExtendedMetadata() {
        return getManager().getDefaultExtendedMetadata();
    }

    @Override
    public void setDefaultExtendedMetadata(ExtendedMetadata defaultExtendedMetadata) {
        getManager().setDefaultExtendedMetadata(defaultExtendedMetadata);
    }

    @Override
    public boolean isRefreshRequired() {
        return getManager().isRefreshRequired();
    }

    @Override
    public void setRefreshRequired(boolean refreshRequired) {
        getManager().setRefreshRequired(refreshRequired);
    }

    @Override
    public void setRefreshCheckInterval(long refreshCheckInterval) {
        this.refreshInterval = refreshCheckInterval;
    }

    @Override
    public void setKeyManager(KeyManager keyManager) {
        getManager().setKeyManager(keyManager);
    }

    @Override
    public void setTLSConfigurer(TLSProtocolConfigurer configurer) {
        getManager().setTLSConfigurer(configurer);
    }

    @Override
    protected void doAddMetadataProvider(MetadataProvider provider, List<MetadataProvider> providerList) {
        getManager().doAddMetadataProvider(provider, providerList);
    }

    @Override
    public void setRequireValidMetadata(boolean requireValidMetadata) {
        getManager().setRequireValidMetadata(requireValidMetadata);
    }

    @Override
    public MetadataFilter getMetadataFilter() {
        return getManager().getMetadataFilter();
    }

    @Override
    public void setMetadataFilter(MetadataFilter newFilter) throws MetadataProviderException {
        getManager().setMetadataFilter(newFilter);
    }

    @Override
    public XMLObject getMetadata() throws MetadataProviderException {
        return getManager().getMetadata();
    }

    @Override
    public EntitiesDescriptor getEntitiesDescriptor(String name) throws MetadataProviderException {
        return getManager().getEntitiesDescriptor(name);
    }

    @Override
    public EntityDescriptor getEntityDescriptor(String entityID) throws MetadataProviderException {
        return getManager().getEntityDescriptor(entityID);
    }

    @Override
    public List<RoleDescriptor> getRole(String entityID, QName roleName) throws MetadataProviderException {
        return getManager().getRole(entityID, roleName);
    }

    @Override
    public RoleDescriptor getRole(String entityID, QName roleName, String supportedProtocol) throws MetadataProviderException {
        return getManager().getRole(entityID, roleName, supportedProtocol);
    }

    @Override
    public List<Observer> getObservers() {
        return getManager().getObservers();
    }

    @Override
    protected void emitChangeEvent() {
        getManager().emitChangeEvent();
    }

    @Override
    public boolean requireValidMetadata() {
        return getManager().requireValidMetadata();
    }

    @Override
    public void destroy() {
        if (timer != null) {
            timer.cancel();
            timer.purge();
            timer = null;
        }
        for (Map.Entry<IdentityZone,ExtensionMetadataManager> manager : metadataManagers.entrySet()) {
            manager.getValue().destroy();
        }
        metadataManagers.clear();
        super.destroy();
    }

    @Override
    public ExtendedMetadata getExtendedMetadata(String entityID) throws MetadataProviderException {
        return super.getExtendedMetadata(entityID);
    }

    protected void refreshZoneManager(ExtensionMetadataManager manager) {
        try {

            log.trace("Executing metadata refresh task");

            // Invoking getMetadata performs a refresh in case it's needed
            // Potentially expensive operation, but other threads can still load existing cached data
            for (MetadataProvider provider : manager.getProviders()) {
                provider.getMetadata();
            }

            // Refresh the metadataManager if needed
            if (manager.isRefreshRequired()) {
                manager.refreshMetadata();
            }

        } catch (Throwable e) {
            log.warn("Metadata refreshing has failed", e);
        }
    }

    //just so that we can override protected methods
    public static class ExtensionMetadataManager extends CachingMetadataManager {
        public ExtensionMetadataManager(List<MetadataProvider> providers) throws MetadataProviderException {
            super(providers);
            //disable internal timers (they only get created when afterPropertiesSet)
            setRefreshCheckInterval(0);
        }

        @Override
        public EntityDescriptor getEntityDescriptor(String entityID) throws MetadataProviderException {
            return super.getEntityDescriptor(entityID);
        }

        @Override
        public EntityDescriptor getEntityDescriptor(byte[] hash) throws MetadataProviderException {
            return super.getEntityDescriptor(hash);
        }

        @Override
        public String getEntityIdForAlias(String entityAlias) throws MetadataProviderException {
            return super.getEntityIdForAlias(entityAlias);
        }

        @Override
        public ExtendedMetadata getExtendedMetadata(String entityID) throws MetadataProviderException {
            return super.getExtendedMetadata(entityID);
        }

        @Override
        public void refreshMetadata() {
            super.refreshMetadata();
        }

        @Override
        public void addMetadataProvider(MetadataProvider newProvider) throws MetadataProviderException {
            super.addMetadataProvider(newProvider);
        }

        @Override
        public void destroy() {
            super.destroy();
        }

        @Override
        public List<ExtendedMetadataDelegate> getAvailableProviders() {
            return super.getAvailableProviders();
        }

        @Override
        public ExtendedMetadata getDefaultExtendedMetadata() {
            return super.getDefaultExtendedMetadata();
        }

        @Override
        public String getDefaultIDP() throws MetadataProviderException {
            return super.getDefaultIDP();
        }

        @Override
        public String getHostedSPName() {
            return super.getHostedSPName();
        }

        @Override
        public Set<String> getIDPEntityNames() {
            return super.getIDPEntityNames();
        }

        @Override
        public PKIXValidationInformationResolver getPKIXResolver(MetadataProvider provider, Set<String> trustedKeys, Set<String> trustedNames) {
            return super.getPKIXResolver(provider, trustedKeys, trustedNames);
        }

        @Override
        public List<MetadataProvider> getProviders() {
            return super.getProviders();
        }

        @Override
        public Set<String> getSPEntityNames() {
            return super.getSPEntityNames();
        }

        @Override
        public SignatureTrustEngine getTrustEngine(MetadataProvider provider) {
            return super.getTrustEngine(provider);
        }

        @Override
        public void initializeProvider(ExtendedMetadataDelegate provider) throws MetadataProviderException {
            super.initializeProvider(provider);
        }

        @Override
        public void initializeProviderData(ExtendedMetadataDelegate provider) throws MetadataProviderException {
            super.initializeProviderData(provider);
        }

        @Override
        public void initializeProviderFilters(ExtendedMetadataDelegate provider) throws MetadataProviderException {
            super.initializeProviderFilters(provider);
        }

        @Override
        public boolean isIDPValid(String idpID) {
            return super.isIDPValid(idpID);
        }

        @Override
        public boolean isRefreshRequired() {
            return super.isRefreshRequired();
        }

        @Override
        public boolean isSPValid(String spID) {
            return super.isSPValid(spID);
        }

        @Override
        public List<String> parseProvider(MetadataProvider provider) throws MetadataProviderException {
            return super.parseProvider(provider);
        }

        @Override
        public void removeMetadataProvider(MetadataProvider provider) {
            super.removeMetadataProvider(provider);
        }

        @Override
        public void setDefaultExtendedMetadata(ExtendedMetadata defaultExtendedMetadata) {
            super.setDefaultExtendedMetadata(defaultExtendedMetadata);
        }

        @Override
        public void setDefaultIDP(String defaultIDP) {
            super.setDefaultIDP(defaultIDP);
        }

        @Override
        public void setHostedSPName(String hostedSPName) {
            super.setHostedSPName(hostedSPName);
        }

        @Override
        public void setKeyManager(KeyManager keyManager) {
            super.setKeyManager(keyManager);
        }

        @Override
        public void setProviders(List<MetadataProvider> newProviders) throws MetadataProviderException {
            super.setProviders(newProviders);
        }

        @Override
        public void setRefreshCheckInterval(long refreshCheckInterval) {
            super.setRefreshCheckInterval(refreshCheckInterval);
        }

        @Override
        public void setRefreshRequired(boolean refreshRequired) {
            super.setRefreshRequired(refreshRequired);
        }

        @Override
        public void setTLSConfigurer(TLSProtocolConfigurer configurer) {
            super.setTLSConfigurer(configurer);
        }

        @Override
        public void doAddMetadataProvider(MetadataProvider provider, List<MetadataProvider> providerList) {
            super.doAddMetadataProvider(provider, providerList);
        }

        @Override
        public void emitChangeEvent() {
            super.emitChangeEvent();
        }

        @Override
        public EntitiesDescriptor getEntitiesDescriptor(String name) throws MetadataProviderException {
            return super.getEntitiesDescriptor(name);
        }

        @Override
        public XMLObject getMetadata() throws MetadataProviderException {
            return super.getMetadata();
        }

        @Override
        public MetadataFilter getMetadataFilter() {
            return super.getMetadataFilter();
        }

        @Override
        public List<Observer> getObservers() {
            return super.getObservers();
        }

        @Override
        public List<RoleDescriptor> getRole(String entityID, QName roleName) throws MetadataProviderException {
            return super.getRole(entityID, roleName);
        }

        @Override
        public RoleDescriptor getRole(String entityID, QName roleName, String supportedProtocol) throws MetadataProviderException {
            return super.getRole(entityID, roleName, supportedProtocol);
        }

        @Override
        public void setMetadataFilter(MetadataFilter newFilter) throws MetadataProviderException {
            super.setMetadataFilter(newFilter);
        }

        @Override
        public void setRequireValidMetadata(boolean requireValidMetadata) {
            super.setRequireValidMetadata(requireValidMetadata);
        }

        @Override
        public boolean requireValidMetadata() {
            return super.requireValidMetadata();
        }
    }

    public static class MetadataProviderObserver implements ObservableMetadataProvider.Observer {
        private ExtensionMetadataManager manager;

        public MetadataProviderObserver(ExtensionMetadataManager manager) {
            this.manager = manager;
        }

        public void onEvent(MetadataProvider provider) {
            manager.setRefreshRequired(true);
        }
    }
}
