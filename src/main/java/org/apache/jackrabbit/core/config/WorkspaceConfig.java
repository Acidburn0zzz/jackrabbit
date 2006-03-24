/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  The ASF licenses this file to You
 * under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License.
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
package org.apache.jackrabbit.core.config;

/**
 * Workspace configuration. This configuration class is used to
 * create configured workspace objects.
 * <p>
 * The contained configuration information are: the home directory and name
 * of the workspace, and the file system, the persistence manager, and the
 * search index configuration. The search index is an optional part of the
 * configuration.
 */
public class WorkspaceConfig {

    /**
     * Workspace home directory.
     */
    private final String home;

    /**
     * Workspace name.
     */
    private final String name;

    /**
     * Workspace file system configuration.
     */
    private FileSystemConfig fsc;

    /**
     * Workspace persistence manager configuration.
     */
    private PersistenceManagerConfig pmc;

    /**
     * Workspace search index configuration. Can be <code>null</code>.
     */
    private SearchConfig sc;

    /**
     * Creates a workspace configuration object.
     *
     * @param home home directory
     * @param name workspace name
     * @param fsc file system configuration
     * @param pmc persistence manager configuration
     * @param sc search index configuration
     */
    public WorkspaceConfig(String home, String name, FileSystemConfig fsc,
            PersistenceManagerConfig pmc, SearchConfig sc) {
        this.home = home;
        this.name = name;
        this.fsc = fsc;
        this.pmc = pmc;
        this.sc = sc;
    }

    /**
     * Returns the workspace home directory.
     *
     * @return workspace home directory
     */
    public String getHomeDir() {
        return home;
    }

    /**
     * Returns the workspace name.
     *
     * @return the workspace name
     */
    public String getName() {
        return name;
    }

    /**
     * Returns the file system configuration.
     *
     * @return file system configuration
     */
    public FileSystemConfig getFileSystemConfig() {
        return fsc;
    }

    /**
     * Returns the workspace persistence manager configuration.
     *
     * @return persistence manager configuration
     */
    public PersistenceManagerConfig getPersistenceManagerConfig() {
        return pmc;
    }

    /**
     * Returns the workspace search index configuration. Returns
     * <code>null</code> if a search index has not been configured.
     *
     * @return search index configuration, or <code>null</code>
     */
    public SearchConfig getSearchConfig() {
        return sc;
    }

}
