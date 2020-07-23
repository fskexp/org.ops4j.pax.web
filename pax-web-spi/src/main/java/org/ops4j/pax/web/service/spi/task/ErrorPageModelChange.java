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

import java.util.LinkedList;
import java.util.List;

import org.ops4j.pax.web.service.spi.model.ServerModel;
import org.ops4j.pax.web.service.spi.model.elements.ErrorPageModel;

public class ErrorPageModelChange extends Change {

	private final ServerModel serverModel;
	private ErrorPageModel errorPageModel;
	private final List<ErrorPageModel> errorPageModels = new LinkedList<>();
	private boolean disabled;

	public ErrorPageModelChange(OpCode kind, ServerModel serverModel, ErrorPageModel model) {
		super(kind);
		this.serverModel = serverModel;
		this.errorPageModel = model;
		this.errorPageModels.add(model);
	}

	public ErrorPageModelChange(OpCode op, ServerModel serverModel, List<ErrorPageModel> errorPageModels) {
		super(op);
		this.serverModel = serverModel;
		this.errorPageModels.addAll(errorPageModels);
	}

	public ErrorPageModelChange(OpCode op, ServerModel serverModel, ErrorPageModel filterModel, boolean disabled) {
		super(op);
		this.serverModel = serverModel;
		this.errorPageModel = filterModel;
		this.errorPageModels.add(filterModel);
		this.disabled = disabled;
	}

	@Override
	public void accept(BatchVisitor visitor) {
		visitor.visit(this);
	}

	public ServerModel getServerModel() {
		return serverModel;
	}

	public ErrorPageModel getErrorPageModel() {
		return errorPageModel;
	}

	public List<ErrorPageModel> getErrorPageModels() {
		return errorPageModels;
	}

	public boolean isDisabled() {
		return disabled;
	}

}
