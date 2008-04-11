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

import org.apache.poi.poifs.eventfilesystem.POIFSReader;
import org.apache.poi.hslf.extractor.PowerPointExtractor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.Reader;
import java.io.InputStream;
import java.io.IOException;
import java.io.StringReader;

/**
 * Text extractor for Microsoft PowerPoint presentations.
 */
public class MsPowerPointTextExtractor extends AbstractTextExtractor {

    /**
     * Logger instance.
     */
    private static final Logger logger =
        LoggerFactory.getLogger(MsPowerPointTextExtractor.class);

    /**
     * Force loading of dependent class.
     */
    static {
        POIFSReader.class.getName();
    }

    /**
     * Creates a new <code>MsPowerPointTextExtractor</code> instance.
     */
    public MsPowerPointTextExtractor() {
        super(new String[]{"application/vnd.ms-powerpoint",
                           "application/mspowerpoint"});
    }

    //-------------------------------------------------------< TextExtractor >

    /**
     * {@inheritDoc}
     */
    public Reader extractText(InputStream stream,
                              String type,
                              String encoding) throws IOException {
        try {
            PowerPointExtractor extractor = new PowerPointExtractor(stream);
            return new StringReader(extractor.getText(true, true));
        } catch (RuntimeException e) {
            logger.warn("Failed to extract PowerPoint text content", e);
            return new StringReader("");
        } finally {
            try {
                stream.close();
            } catch (IOException ignored) {
            }
        }
    }
}
