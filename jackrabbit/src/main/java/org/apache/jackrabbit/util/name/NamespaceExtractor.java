/*
 * $Id$
 *
 * Copyright 2002-2004 Day Management AG, Switzerland.
 *
 * Licensed under the Day RI License, Version 2.0 (the "License"),
 * as a reference implementation of the following specification:
 *
 *   Content Repository API for Java Technology, revision 0.13
 *        <http://www.jcp.org/en/jsr/detail?id=170>
 *
 * You may not use this file except in compliance with the License.
 * You may obtain a copy of the License files at
 *
 *     http://www.day.com/content/en/licenses/day-ri-license-2.0
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.apache.jackrabbit.util.name;

import org.xml.sax.XMLReader;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.ContentHandler;
import org.xml.sax.helpers.XMLReaderFactory;
import org.xml.sax.helpers.DefaultHandler;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.jcr.NamespaceException;
import java.io.FileInputStream;
import java.util.Map;
import java.util.HashMap;

/**
 * Extracts namespace mapping information from an XML file.
 * XML file is parsed and all startPrefixMapping events
 * are intercepted. Scoping of prefix mapping within the XML file
 * may result in multiple namespace using the same prefix. This
 * is handled by mangling the prefix when required.
 *
 * The resulting NamespaceMapping implements NamespaceResolver
 * and can be used by tools (such as o.a.j.tools.nodetype.CompactNodeTypeDefWriter)
 * to resolve namespaces.
 */
public class NamespaceExtractor {
    private static Logger log = LoggerFactory.getLogger(NamespaceExtractor.class);
    private final NamespaceMapping mapping = new NamespaceMapping();
    private final Map basePrefixes = new HashMap();
    private String defaultBasePrefix;

    /**
     * Constructor
     * @param fileName
     * @param dpb
     * @throws NamespaceException
     */
    public NamespaceExtractor(String fileName, String dpb) throws NamespaceException {
        defaultBasePrefix = dpb;
        try{
            ContentHandler handler = new NamespaceHandler();
            XMLReader parser = XMLReaderFactory.createXMLReader();
            parser.setContentHandler(handler);
            parser.parse(new InputSource(new FileInputStream(fileName)));
        } catch(Exception e){
            throw new NamespaceException();
        }
    }

    /**
     * getNamespaceMapping
     * @return a NamespaceMapping
     */
    public NamespaceMapping getNamespaceMapping(){
        return mapping;
    }

    /**
     * SAX ContentHandler that reacts to namespace mappings in incoming XML.
     */
    private class NamespaceHandler extends DefaultHandler{
        public void startPrefixMapping(String prefix, String uri) throws SAXException {
            if (uri == null) uri = "";

            //Replace the empty prefix with the defaultBasePrefix
            if (prefix == null || prefix.equals("")){
                prefix = defaultBasePrefix;
            }

            try{
                // if prefix already used
                if (mapping.hasPrefix(prefix)){
                    int c;
                    Integer co = (Integer) basePrefixes.get(prefix);
                    if (co == null) {
                        basePrefixes.put(prefix, new Integer(1));
                        c = 1;
                    } else {
                        c = co.intValue() + 1;
                        basePrefixes.put(prefix, new Integer(c));
                    }
                    prefix = prefix + "_" + c;
                }
                mapping.setMapping(prefix, uri);
            } catch(NamespaceException e){
                String msg = e.getMessage();
                log.debug(msg);
            }
        }
    }
}
