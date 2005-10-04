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
package org.apache.jackrabbit.command.core;

import javax.jcr.Node;

import org.apache.commons.chain.Command;
import org.apache.commons.chain.Context;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jackrabbit.command.CommandHelper;

/**
 * Add a node to the current working node.
 */
public class AddNode implements Command
{
	/** logger */
	private static Log log = LogFactory.getLog(AddNode.class);

	// ---------------------------- < keys >

	/** Node type key */
	private String typeKey = "type";

	/** Node name key */
	private String relPathKey = "relPath";

	/**
	 * @inheritDoc
	 */
	public boolean execute(Context ctx) throws Exception
	{
		Node node = CommandHelper.getCurrentNode(ctx);
		String nodeType = (String) ctx.get(this.typeKey);
		String name = (String) ctx.get(this.relPathKey);

		if (log.isDebugEnabled())
		{
			log.debug("adding node at " + node.getPath() + "/" + name);
		}

		if (nodeType == null)
		{
			node.addNode(name);
		} else
		{
			node.addNode(name, nodeType);
		}
		return false;
	}

	/**
	 * @return Returns the nodeTypeKey.
	 */
	public String getTypeKey()
	{
		return typeKey;
	}

	/**
	 * @param nodeTypeKey
	 *            Set the context attribute key for the node type attribute.
	 */
	public void setTypeKey(String nodeTypeKey)
	{
		this.typeKey = nodeTypeKey;
	}

	/**
	 * @return Returns the relative path.
	 */
	public String getRelPathKey()
	{
		return relPathKey;
	}

	/**
	 * @param relPathKey
	 *            The relative path key to set.
	 */
	public void setRelPathKey(String relPathKey)
	{
		this.relPathKey = relPathKey;
	}
}
