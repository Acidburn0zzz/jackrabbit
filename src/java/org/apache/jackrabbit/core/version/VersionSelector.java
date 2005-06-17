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
package org.apache.jackrabbit.core.version;

import javax.jcr.RepositoryException;
import javax.jcr.version.Version;
import javax.jcr.version.VersionHistory;

/**
 * This Interface defines the version selector that needs to provide a version,
 * given some hints and a version history
 */
public interface VersionSelector {
    
    /**
     * Selects a version of the given version history. If this VersionSelector
     * is unable to select one, it can return <code>null</code>.
     *
     * @param versionHistory
     * @return A version or <code>null</code>.
     * @throws RepositoryException if an error occurrs.
     */
    Version select(VersionHistory versionHistory) throws RepositoryException;
}
