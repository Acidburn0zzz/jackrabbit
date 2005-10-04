/*
 * Copyright 2002-2004 The Apache Software Foundation.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 * 
 */
package org.apache.jackrabbit.command.collect;

import java.util.ArrayList;
import java.util.Collection;

import javax.jcr.Node;

import org.apache.commons.chain.Command;
import org.apache.commons.chain.Context;
import org.apache.commons.logging.Log;
import org.apache.commons.logging.LogFactory;
import org.apache.jackrabbit.command.CommandHelper;
import org.apache.jackrabbit.util.ChildrenCollectorFilter;

/**
 * Collect the children items from the given node and store them under the given
 * key.
 */
public abstract class AbstractCollect implements Command
{
	/** logger */
	private static Log log = LogFactory.getLog(AbstractCollect.class);

	// ---------------------------- < keys >

	/** path key. Default value is "." */
	private String srcPathKey = "srcPath";

	/** context attribute key for the depth attribute. */
	private String depthKey = "depth";

	/** context attribute key for the name pattern attribute. Default value is "*" */
	private String namePatternKey = "namePattern";

	/** context attribute key for the destination attribute */
	private String destKey = "collected";

	/**
	 * @inheritDoc
	 */
	public final boolean execute(Context ctx) throws Exception
	{
		if (this.destKey == null || this.destKey.length() == 0)
		{
			throw new IllegalStateException("target variable is not set");
		}
		String relPath = (String) ctx.get(this.srcPathKey);
		if (relPath == null)
		{
			relPath = ".";
		}
		String namePattern = (String) ctx.get(this.namePatternKey);
		if (namePattern == null || namePattern.length() == 0)
		{
			namePattern = "*";
		}

		int depth = Integer.parseInt((String) ctx.get(this.depthKey));

		Node node = CommandHelper.getNode(ctx, relPath);
		if (log.isDebugEnabled())
		{
			log.debug("collecting nodes from " + node.getPath() + " depth="
					+ depth + " pattern=" + namePattern
					+ " into target variable " + this.destKey);
		}

		Collection items = new ArrayList();
		ChildrenCollectorFilter collector = new ChildrenCollectorFilter(
				namePattern, items, isCollectNodes(), isCollectProperties(),
				depth);
		collector.visit(node);
		ctx.put(this.destKey, items.iterator());

		return false;
	}

	/**
	 * @return Returns the depthKey.
	 */
	public String getDepthKey()
	{
		return depthKey;
	}

	/**
	 * @param depthKey
	 *            Set the context attribute key for the depth attribute
	 */
	public void setDepthKey(String depthKey)
	{
		this.depthKey = depthKey;
	}

	/**
	 * @return Returns the namePatternKey.
	 */
	public String getNamePatternKey()
	{
		return namePatternKey;
	}

	/**
	 * @param namePatternKey
	 *            context attribute key for the name pattern attribute
	 */
	public void setNamePatternKey(String namePatternKey)
	{
		this.namePatternKey = namePatternKey;
	}

	/** Collect nodes flag */
	protected abstract boolean isCollectNodes();

	/** Collect properties flag */
	protected abstract boolean isCollectProperties();

	public String getDestKey()
	{
		return destKey;
	}

	public void setDestKey(String destKey)
	{
		this.destKey = destKey;
	}

	public String getSrcPathKey()
	{
		return srcPathKey;
	}

	public void setSrcPathKey(String srcPathKey)
	{
		this.srcPathKey = srcPathKey;
	}
}
