/*
 * Copyright 2005 The Apache Software Foundation.
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
package org.apache.jackrabbit.webdav;

import org.jdom.Document;
import org.jdom.Element;
import org.apache.jackrabbit.webdav.property.DavPropertyNameSet;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;

/**
 * MultiStatus representing the content of a multistatus response body and
 * allows to retrieve the Xml representation.
 */
public class MultiStatus implements DavConstants {

    private ArrayList responses = new ArrayList();
    private String responseDescription;

    /**
     * Add response(s) to this multistatus, in order to build a multistatus for
     * responding to a PROPFIND request.
     *
     * @param resource The resource to add property from
     * @param propNameSet The requested property names of the PROPFIND request
     * @param propFindType
     * @param depth
     */
    public void addResourceProperties(DavResource resource, DavPropertyNameSet propNameSet,
				      int propFindType, int depth) {
	addResponse(new MultiStatusResponse(resource, propNameSet, propFindType));
	if (depth > 0) {
	    DavResourceIterator iter = resource.getMembers();
	    while (iter.hasNext()) {
		addResourceProperties(iter.nextResource(), propNameSet, propFindType, depth-1);
	    }
	}
    }

    /**
     * Add response(s) to this multistatus, in order to build a multistatus e.g.
     * in order to respond to a PROPFIND request. Please note, that in terms
     * of PROPFIND, this method would correspond to a
     * {@link DavConstants#PROPFIND_BY_PROPERTY} propfind type.
     *
     * @param resource The resource to add property from
     * @param propNameSet The requested property names of the PROPFIND request
     * @param depth
     * @see #addResourceProperties(DavResource, DavPropertyNameSet, int, int) for
     * the corresponding method that allows to specify the type explicitely.
     */
    public void addResourceProperties(DavResource resource, DavPropertyNameSet propNameSet,
				      int depth) {
	addResourceProperties(resource, propNameSet, PROPFIND_BY_PROPERTY, depth);
    }

    /**
     * Add response(s) to this multistatus, in order to build a multistatus
     * as returned for failed COPY, MOVE, LOCK or DELETE requests
     *
     * @param resource
     * @param status
     * @param depth
     */
    public void addResourceStatus(DavResource resource, int status, int depth) {
	addResponse(new MultiStatusResponse(resource.getHref(), status));
	if (depth > 0) {
	    DavResourceIterator iter = resource.getMembers();
	    while (iter.hasNext()) {
		addResourceStatus(iter.nextResource(), status, depth-1);
	    }
	}
    }

    /**
     * Add a <code>MultiStatusResponse</code> element to this <code>MultiStatus</code>
     *
     * @param response
     */
    public void addResponse(MultiStatusResponse response) {
	responses.add(response);
    }

    /**
     * Returns the multistatus responses present as array.
     *
     * @return array of all {@link MultiStatusResponse responses} present in this
     * multistatus.
     */
    public MultiStatusResponse[] getResponses() {
	return (MultiStatusResponse[]) responses.toArray(new MultiStatusResponse[responses.size()]);
    }

    /**
     * Set the response description.
     *
     * @param responseDescription
     */
    public void setResponseDescription(String responseDescription) {
        this.responseDescription = responseDescription;
    }

    /**
     * Returns the response description.
     *
     * @return responseDescription
     */
    public String getResponseDescription() {
	return responseDescription;
    }

    /**
     * Return the Xml representation of this <code>MultiStatus</code>.
     *
     * @return Xml document
     */
    public Document toXml() {
	Element multistatus = new Element(XML_MULTISTATUS, NAMESPACE);
        Iterator it = responses.iterator();
	while(it.hasNext()) {
	    multistatus.addContent(((MultiStatusResponse)it.next()).toXml());
	}
        if (responseDescription != null) {
            multistatus.addContent(new Element(XML_RESPONSEDESCRIPTION, NAMESPACE).setText(responseDescription));
        }
	return new Document(multistatus);
    }

    /**
     * Build a <code>MultiStatus</code> from the specified xml document.
     *
     * @param multistatusDocument
     * @return new <code>MultiStatus</code> instance.
     * @throws IllegalArgumentException if the given document is <code>null</code>
     * or does not provide the required element.
     */
    public static MultiStatus createFromXml(Document multistatusDocument) {
        if (multistatusDocument == null) {
	    throw new IllegalArgumentException("Cannot create a MultiStatus object from a null xml document.");
	}

	Element msElem = multistatusDocument.getRootElement();
	if (!(XML_MULTISTATUS.equals(msElem.getName()) && NAMESPACE.equals(msElem.getNamespace()))) {
	    throw new IllegalArgumentException("DAV:multistatus element expected.");
	}

        MultiStatus multistatus = new MultiStatus();

	List respList = msElem.getChildren(XML_RESPONSE, NAMESPACE);
	Iterator it = respList.iterator();
	while (it.hasNext()) {
            MultiStatusResponse response = MultiStatusResponse.createFromXml((Element)it.next());
            multistatus.addResponse(response);
	}

	// optional response description on the multistatus element
	multistatus.setResponseDescription(msElem.getChildText(XML_RESPONSEDESCRIPTION, NAMESPACE));
        return multistatus;
    }
}
