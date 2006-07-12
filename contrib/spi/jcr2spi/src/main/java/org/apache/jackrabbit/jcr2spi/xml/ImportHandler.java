/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.jcr2spi.xml;

import org.apache.jackrabbit.name.NamespaceResolver;
import org.apache.jackrabbit.name.AbstractNamespaceResolver;
import org.apache.jackrabbit.name.QName;
import org.xml.sax.Attributes;
import org.xml.sax.ContentHandler;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;
import org.xml.sax.helpers.NamespaceSupport;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import javax.jcr.NamespaceException;
import javax.jcr.RepositoryException;
import javax.jcr.NamespaceRegistry;
import org.apache.jackrabbit.spi.IdFactory;

/**
 * An <code>ImportHandler</code> instance can be used to import serialized
 * data in System View XML or Document View XML. Processing of the XML is
 * handled by specialized <code>ContentHandler</code>s
 * (i.e. <code>SysViewImportHandler</code> and <code>DocViewImportHandler</code>).
 * <p/>
 * The actual task of importing though is delegated to the implementation of
 * the <code>{@link Importer}</code> interface.
 * <p/>
 * <b>Important Note:</b>
 * <p/>
 * These SAX Event Handlers expect that Namespace URI's and local names are
 * reported in the <code>start/endElement</code> events and that
 * <code>start/endPrefixMapping</code> events are reported
 * (i.e. default SAX2 Namespace processing).
 */
public class ImportHandler extends DefaultHandler {

    private static Logger log = LoggerFactory.getLogger(ImportHandler.class);

    private final Importer importer;
    private final NamespaceRegistry nsReg;
    private final NamespaceResolver nsResolver;
    private final IdFactory idFactory;

    private Locator locator;
    private ContentHandler targetHandler;
    private boolean systemViewXML;
    private boolean initialized;

    private final NamespaceContext nsContext;

    /**
     * this flag is used to determine whether a namespace context needs to be
     * started in the startElement event or if the namespace context has already
     * been started in a preceeding startPrefixMapping event;
     * the flag is set per element in the first startPrefixMapping event and is
     * cleared again in the following startElement event;
     */
    protected boolean nsContextStarted;

    // DIFF JACKRABBIT: pass NamespaceRegistry instead of impl.
    public ImportHandler(Importer importer, NamespaceResolver nsResolver,
                         NamespaceRegistry nsReg, IdFactory idFactory) {
        this.importer = importer;
        this.nsResolver = nsResolver;
        this.nsReg = nsReg;
        this.idFactory = idFactory;

        nsContext = new NamespaceContext();
    }

    //---------------------------------------------------------< ErrorHandler >
    /**
     * {@inheritDoc}
     */
    public void warning(SAXParseException e) throws SAXException {
        // log exception and carry on...
        log.warn("warning encountered at line: " + e.getLineNumber()
                + ", column: " + e.getColumnNumber()
                + " while parsing XML stream", e);
    }

    /**
     * {@inheritDoc}
     */
    public void error(SAXParseException e) throws SAXException {
        // log exception and carry on...
        log.error("error encountered at line: " + e.getLineNumber()
                + ", column: " + e.getColumnNumber()
                + " while parsing XML stream: " + e.toString());
    }

    /**
     * {@inheritDoc}
     */
    public void fatalError(SAXParseException e) throws SAXException {
        // log and re-throw exception
        log.error("fatal error encountered at line: " + e.getLineNumber()
                + ", column: " + e.getColumnNumber()
                + " while parsing XML stream: " + e.toString());
        throw e;
    }

    //-------------------------------------------------------< ContentHandler >
    /**
     * {@inheritDoc}
     */
    public void startDocument() throws SAXException {
        systemViewXML = false;
        initialized = false;
        targetHandler = null;

        /**
         * start initial context containing existing mappings reflected
         * by nsResolver
         */
        nsContext.reset();
        nsContext.pushContext();
        try {
            String[] uris = nsReg.getURIs();
            for (int i = 0; i < uris.length; i++) {
                nsContext.declarePrefix(nsResolver.getPrefix(uris[i]), uris[i]);
            }
        } catch (RepositoryException re) {
            throw new SAXException(re);
        }

        // initialize flag
        nsContextStarted = false;
    }

    /**
     * {@inheritDoc}
     */
    public void endDocument() throws SAXException {
        // delegate to target handler
        targetHandler.endDocument();
        // cleanup
        nsContext.reset();
    }

    /**
     * {@inheritDoc}
     */
    public void startPrefixMapping(String prefix, String uri)
            throws SAXException {
        // check if new context needs to be started
        if (!nsContextStarted) {
            // entering new namespace context
            nsContext.pushContext();
            nsContextStarted = true;
        }

        try {
            // this will trigger NamespaceException if namespace is unknown
            nsContext.getPrefix(uri);
        } catch (NamespaceException nse) {
            // namespace is not yet registered ...
            try {
                String newPrefix;
                if ("".equals(prefix)) {
                    /**
                     * the xml document specifies a default namespace
                     * (i.e. an empty prefix); we need to create a random
                     * prefix as the empty prefix is reserved according
                     * to the JCR spec.
                     */
                    newPrefix = getUniquePrefix(uri);
                } else {
                    newPrefix = prefix;
                }
                // register new namespace
                nsReg.registerNamespace(newPrefix, uri);
            } catch (RepositoryException re) {
                throw new SAXException(re);
            }
        }
        // map namespace in this context to given prefix
        nsContext.declarePrefix(prefix, uri);
    }

    /**
     * {@inheritDoc}
     */
    public void endPrefixMapping(String prefix) throws SAXException {
        /**
         * nothing to do here as namespace context has already been popped
         * in endElement event
         */
    }

    /**
     * {@inheritDoc}
     */
    public void startElement(String namespaceURI, String localName, String qName,
                             Attributes atts) throws SAXException {
        // check if new context needs to be started
        if (!nsContextStarted) {
            // there hasn't been a preceeding startPrefixMapping event
            // so enter new namespace context
            nsContext.pushContext();
        } else {
            // reset flag
            nsContextStarted = false;
        }

        if (!initialized) {
            // the namespace of the first element determines the type of XML
            // (system view/document view)
            systemViewXML = QName.NS_SV_URI.equals(namespaceURI);

            if (systemViewXML) {
                targetHandler = new SysViewImportHandler(importer, nsContext, idFactory);
            } else {
                targetHandler = new DocViewImportHandler(importer, nsContext, idFactory);
            }
            targetHandler.startDocument();
            initialized = true;
        }

        // delegate to target handler
        targetHandler.startElement(namespaceURI, localName, qName, atts);
    }

    /**
     * {@inheritDoc}
     */
    public void characters(char[] ch, int start, int length) throws SAXException {
        // delegate to target handler
        targetHandler.characters(ch, start, length);
    }

    /**
     * {@inheritDoc}
     */
    public void endElement(String namespaceURI, String localName, String qName)
            throws SAXException {
        // leaving element, pop namespace context
        nsContext.popContext();

        // delegate to target handler
        targetHandler.endElement(namespaceURI, localName, qName);
    }

    /**
     * {@inheritDoc}
     */
    public void setDocumentLocator(Locator locator) {
        this.locator = locator;
    }

    //--------------------------------------------------------< inner classes >
    /**
     * <code>NamespaceContext</code> supports scoped namespace declarations.
     */
    class NamespaceContext extends AbstractNamespaceResolver {

        private final NamespaceSupport nsContext;

        /**
         * NamespaceSupport doesn't accept "" as default uri;
         * internally we're using " " instead
         */
        private static final String DUMMY_DEFAULT_URI = " ";

        NamespaceContext() {
            nsContext = new NamespaceSupport();
        }

        /**
         * {@inheritDoc}
         */
        void popContext() {
            nsContext.popContext();
        }

        /**
         * {@inheritDoc}
         */
        void pushContext() {
            nsContext.pushContext();
        }

        /**
         * {@inheritDoc}
         */
        void reset() {
            nsContext.reset();
        }

        /**
         * {@inheritDoc}
         */
        boolean declarePrefix(String prefix, String uri) {
            if (QName.NS_DEFAULT_URI.equals(uri)) {
                uri = DUMMY_DEFAULT_URI;
            }
            return nsContext.declarePrefix(prefix, uri);
        }

        //------------------------------------------------< NamespaceResolver >
        /**
         * {@inheritDoc}
         */
        public String getURI(String prefix) throws NamespaceException {
            String uri = nsContext.getURI(prefix);
            if (uri == null) {
                throw new NamespaceException("unknown prefix");
            } else if (DUMMY_DEFAULT_URI.equals(uri)) {
                return QName.NS_DEFAULT_URI;
            } else {
                return uri;
            }
        }

        /**
         * {@inheritDoc}
         */
        public String getPrefix(String uri) throws NamespaceException {
            if (QName.NS_DEFAULT_URI.equals(uri)) {
                uri = DUMMY_DEFAULT_URI;
            }
            String prefix = nsContext.getPrefix(uri);
            if (prefix == null) {
                /**
                 * NamespaceSupport#getPrefix will never return the empty
                 * (default) prefix; we have to do a reverse-lookup to check
                 * whether it's the current default namespace
                 */
                if (uri.equals(nsContext.getURI(QName.NS_EMPTY_PREFIX))) {
                    return QName.NS_EMPTY_PREFIX;
                }
                throw new NamespaceException("unknown uri");
            }
            return prefix;
        }
    }

    /**
     * Returns a prefix that is unique among the already registered prefixes.
     *
     * @param uriHint namespace uri that serves as hint for the prefix generation
     * @return a unique prefix
     */
    // DIFF JACKRABBIT: method moved from NamespaceRegistryImpl (only used here)
    public String getUniquePrefix(String uriHint) throws RepositoryException {
        // @todo smarter unique prefix generation
        return "_pre" + (nsReg.getPrefixes().length + 1);
    }
}
