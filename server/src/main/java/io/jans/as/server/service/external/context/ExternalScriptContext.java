/*
 * Janssen Project software is available under the Apache License (2004). See http://www.apache.org/licenses/ for full text.
 *
 * Copyright (c) 2020, Janssen Project
 */

package io.jans.as.server.service.external.context;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.commons.net.util.SubnetUtils;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import io.jans.as.model.util.Util;
import io.jans.as.server.util.ServerUtil;
import io.jans.orm.PersistenceEntryManager;
import io.jans.orm.exception.EntryPersistenceException;
import io.jans.orm.model.base.CustomEntry;

/**
 * Holds object required in custom scripts
 *
 * @author Yuriy Movchan  Date: 07/01/2015
 */

public class ExternalScriptContext extends io.jans.service.external.context.ExternalScriptContext {

    private static final Logger log = LoggerFactory.getLogger(ExternalScriptContext.class);

    private final PersistenceEntryManager ldapEntryManager;

    public ExternalScriptContext(HttpServletRequest httpRequest) {
        this(httpRequest, null);
    }

    public ExternalScriptContext(HttpServletRequest httpRequest, HttpServletResponse httpResponse) {
    	super(httpRequest, httpResponse);
        this.ldapEntryManager = ServerUtil.getLdapManager();
    }

    public PersistenceEntryManager getPersistenceEntryManager() {
        return ldapEntryManager;
    }

    public boolean isInNetwork(String cidrNotation) {
        final String ip = getIpAddress();
        if (Util.allNotBlank(ip, cidrNotation)) {
            final SubnetUtils utils = new SubnetUtils(cidrNotation);
            return utils.getInfo().isInRange(ip);
        }
        return false;
    }

    protected CustomEntry getEntryByDn(String dn, String... ldapReturnAttributes) {
        try {
            return ldapEntryManager.find(dn, CustomEntry.class, ldapReturnAttributes);
        } catch (EntryPersistenceException epe) {
            log.error("Failed to find entry '{}'", dn);
        }

        return null;
    }

    protected String getEntryAttributeValue(String dn, String attributeName) {
        final CustomEntry entry = getEntryByDn(dn, attributeName);
        if (entry != null) {
            final String attributeValue = entry.getCustomAttributeValue(attributeName);
            return attributeValue;
        }

        return "";
    }
}
