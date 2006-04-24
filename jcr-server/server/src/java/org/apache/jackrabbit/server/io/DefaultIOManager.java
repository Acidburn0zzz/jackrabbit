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
package org.apache.jackrabbit.server.io;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * <code>DefaultIOManager</code>...
 */
public class DefaultIOManager extends IOManagerImpl {

    private static Logger log = LoggerFactory.getLogger(DefaultIOManager.class);

    /**
     * Creates a new <code>DefaultIOManager</code> and populates the internal
     * list of <code>IOHandler</code>s by the defaults.
     *
     * @see #init()
     */
    public DefaultIOManager() {
        init();
    }

    /**
     * Creates a new <code>DefaultIOManager</code>. The if the flag is set to
     * false no <code>IOHandler</code>s are added to the internal list.
     *
     * @deprecated Use {@link IOManagerImpl} instead.
     */
    protected DefaultIOManager(boolean initDefaults) {
        if (initDefaults) {
           init();
        }
    }

    /**
     * Add the predefined <code>IOHandler</code>s to this manager. This includes
     * <ul>
     * <li>{@link ZipHandler}</li>
     * <li>{@link XmlHandler}</li>
     * <li>{@link DirListingExportHandler}</li>
     * <li>{@link DefaultHandler}.</li>
     * </ul>
     */
    protected void init() {
        addIOHandler(new ZipHandler(this));
        addIOHandler(new XmlHandler(this));
        addIOHandler(new DirListingExportHandler(this));
        addIOHandler(new DefaultHandler(this));
    }
}