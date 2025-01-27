/*
 * Copyright 2020 OPS4J.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.spi.task;

import org.ops4j.pax.web.service.spi.model.ServerModel;
import org.ops4j.pax.web.service.spi.model.ServletContextModel;

/**
 * Adding new {@link ServletContextModel}
 */
public class ServletContextModelChange extends Change {

	private final ServerModel serverModel;
	private final ServletContextModel servletContextModel;

	public ServletContextModelChange(OpCode op, ServerModel model, ServletContextModel servletContextModel) {
		super(op);
		this.serverModel = model;
		this.servletContextModel = servletContextModel;
	}

	public ServletContextModel getServletContextModel() {
		return servletContextModel;
	}

	@Override
	public void accept(BatchVisitor visitor) {
		visitor.visit(this);
	}

	@Override
	public String toString() {
		return getKind() + ": " + servletContextModel;
	}

}
