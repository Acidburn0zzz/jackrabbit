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
package org.apache.jackrabbit.core.query.lucene;

import org.apache.lucene.document.Document;
import org.apache.lucene.document.Fieldable;
import org.apache.lucene.search.Query;
import org.apache.lucene.search.TermQuery;
import org.apache.lucene.index.Term;
import org.slf4j.LoggerFactory;
import org.slf4j.Logger;

import java.util.Iterator;
import java.io.IOException;

/**
 * <code>Util</code> provides various static utility methods.
 */
public class Util {

    /**
     * The logger instance for this class.
     */
    private static final Logger log = LoggerFactory.getLogger(Util.class);

    /**
     * Disposes the document <code>old</code>. Closes any potentially open
     * readers held by the document.
     *
     * @param old the document to dispose.
     */
    public static void disposeDocument(Document old) {
        for (Iterator it = old.getFields().iterator(); it.hasNext(); ) {
            Fieldable f = (Fieldable) it.next();
            try {
                if (f.readerValue() != null) {
                    f.readerValue().close();
                } else if (f instanceof LazyTextExtractorField) {
                    LazyTextExtractorField field = (LazyTextExtractorField) f;
                    field.dispose();
                }
            } catch (IOException ex) {
                log.warn("Exception while disposing index document: " + ex);
            }
        }
    }

    /**
     * Returns <code>true</code> if the document is ready to be added to the
     * index. That is all text extractors have finished their work.
     *
     * @param doc the document to check.
     * @return <code>true</code> if the document is ready; <code>false</code>
     *         otherwise.
     */
    public static boolean isDocumentReady(Document doc) {
        for (Iterator it = doc.getFields().iterator(); it.hasNext(); ) {
            Fieldable f = (Fieldable) it.next();
            if (f instanceof LazyTextExtractorField) {
                LazyTextExtractorField field = (LazyTextExtractorField) f;
                if (!field.isExtractorFinished()) {
                    return false;
                }
            }
        }
        return true;
    }

    /**
     * Depending on the index format this method returns a query that matches
     * all nodes that have a property with a given <code>name</code>.
     *
     * @param name    the property name.
     * @param version the index format version.
     * @return Query that matches all nodes that have a property with the given
     *         <code>name</code>.
     */
    public static Query createMatchAllQuery(String name, IndexFormatVersion version) {
        if (version.getVersion() >= IndexFormatVersion.V2.getVersion()) {
            // new index format style
            return new TermQuery(new Term(FieldNames.PROPERTIES_SET, name));
        } else {
            return new MatchAllQuery(name);
        }
    }

    /**
     * Creates an {@link IOException} with <code>t</code> as its cause.
     *
     * @param t the cause.
     */
    public static IOException createIOException(Throwable t) {
        IOException ex = new IOException(t.getMessage());
        ex.initCause(t);
        return ex;
    }
}
