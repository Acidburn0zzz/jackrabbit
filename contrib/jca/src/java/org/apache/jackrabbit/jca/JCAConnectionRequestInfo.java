/*
 * Copyright 2004-2005 The Apache Software Foundation or its licensors,
 *                     as applicable.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.jca;

import javax.jcr.Credentials;
import javax.jcr.SimpleCredentials;
import javax.resource.spi.ConnectionRequestInfo;
import java.util.HashMap;
import java.util.Map;

/**
 * This class encapsulates the credentials for creating a
 * session from the repository.
 */
public final class JCAConnectionRequestInfo
        implements ConnectionRequestInfo {
    
    /**
     * Credentials.
     */
    private final Credentials creds;

    /**
     * Workspace.
     */
    private final String workspace;

    /**
     * Construct the request info.
     */
    public JCAConnectionRequestInfo(JCAConnectionRequestInfo cri) {
        this(cri.creds, cri.workspace);
    }

    /**
     * Construct the request info.
     */
    public JCAConnectionRequestInfo(Credentials creds, String workspace) {
        this.creds = creds;
        this.workspace = workspace;
    }

    /**
     * Return the workspace.
     */
    public String getWorkspace() {
        return workspace;
    }

    /**
     * Return the credentials.
     */
    public Credentials getCredentials() {
        return creds;
    }

    /**
     * Return the hash code.
     */
    public int hashCode() {
        int hash1 = workspace != null ? workspace.hashCode() : 0;
        int hash2 = creds != null ? creds.hashCode() : 0;
        return hash1 ^ hash2;
    }

    /**
     * Return true if equals.
     */
    public boolean equals(Object o) {
        if (o == this) {
            return true;
        } else if (o instanceof JCAConnectionRequestInfo) {
            return equals((JCAConnectionRequestInfo) o);
        } else {
            return false;
        }
    }

    /**
     * Return true if equals.
     */
    private boolean equals(JCAConnectionRequestInfo o) {
        return equals(workspace, o.workspace) &&
                equals(creds, o.creds);
    }

    /**
     * Return true if equals.
     */
    private boolean equals(Object o1, Object o2) {
        if (o1 == o2) {
            return true;
        } else if ((o1 != null) && (o2 != null)) {
            return o1.equals(o2);
        } else {
            return false;
        }
    }

    /**
     * Return true if equals.
     */
    private boolean equals(char[] o1, char[] o2) {
        if (o1 == o2) {
            return true;
        } else if ((o1 != null) && (o2 != null)) {
            return equals(new String(o1), new String(o2));
        } else {
            return false;
        }
    }

    /**
     * Return true if equals.
     */
    private boolean equals(Credentials o1, Credentials o2) {
        if (o1 == o2) {
            return true;
        } else if ((o1 != null) && (o2 != null)) {
            if ((o1 instanceof SimpleCredentials) && (o2 instanceof SimpleCredentials)) {
                return equals((SimpleCredentials) o1, (SimpleCredentials) o2);
            } else {
                return o1.equals(o2);
            }
        } else {
            return false;
        }
    }

    /**
     * This method compares two simple credentials.
     */
    private boolean equals(SimpleCredentials o1, SimpleCredentials o2) {
        if (!equals(o1.getUserID(), o2.getUserID())) {
            return false;
        }

        if (!equals(o1.getPassword(), o2.getPassword())) {
            return false;
        }

        Map m1 = getAttributeMap(o1);
        Map m2 = getAttributeMap(o2);
        return m1.equals(m2);
    }

    /**
     * Return the credentials attributes.
     */
    private Map getAttributeMap(SimpleCredentials creds) {
        HashMap map = new HashMap();
        String[] keys = creds.getAttributeNames();

        for (int i = 0; i < keys.length; i++) {
            map.put(keys[i], creds.getAttribute(keys[i]));
        }

        return map;
    }
}
