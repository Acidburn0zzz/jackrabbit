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
package org.apache.jackrabbit.extractor;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.xml.sax.InputSource;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.transform.sax.SAXSource;
import javax.xml.transform.sax.SAXResult;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerConfigurationException;
import java.io.Reader;
import java.io.InputStream;
import java.io.IOException;
import java.io.StringReader;
import java.io.InputStreamReader;

/**
 * Text extractor for HyperText Markup Language (HTML).
 */
public class HTMLTextExtractor extends AbstractTextExtractor {

    /**
     * Logger instance.
     */
    private static final Logger logger =
        LoggerFactory.getLogger(HTMLTextExtractor.class);

    /**
     * Creates a new <code>HTMLTextExtractor</code> instance.
     */
    public HTMLTextExtractor() {
        super(new String[]{"text/html"});
    }

    //-------------------------------------------------------< TextExtractor >

    /**
     * {@inheritDoc}
     */
    public Reader extractText(InputStream stream,
                              String type,
                              String encoding) throws IOException {
        try {
            TransformerFactory factory = TransformerFactory.newInstance();
            Transformer transformer = factory.newTransformer();
            HTMLParser parser = new HTMLParser();
            SAXResult result = new SAXResult(new DefaultHandler());

            Reader reader;
            if (encoding != null) {
                reader = new InputStreamReader(stream, encoding);
            } else {
                reader = new InputStreamReader(stream);
            }
            SAXSource source = new SAXSource(parser, new InputSource(reader));
            transformer.transform(source, result);

            return new StringReader(parser.getContents());
        } catch (TransformerConfigurationException e) {
            logger.warn("Failed to extract HTML text content", e);
            return new StringReader("");
        } catch (TransformerException e) {
            logger.warn("Failed to extract HTML text content", e);
            return new StringReader("");
        } finally {
            stream.close();
        }
    }
}
