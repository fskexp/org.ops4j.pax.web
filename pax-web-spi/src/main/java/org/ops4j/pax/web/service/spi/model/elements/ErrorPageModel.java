/*
 * Copyright 2008 Alin Dreghiciu.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or
 * implied.
 *
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.ops4j.pax.web.service.spi.model.elements;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Pattern;

import org.ops4j.pax.web.service.spi.model.events.ErrorPageEventData;
import org.ops4j.pax.web.service.spi.whiteboard.WhiteboardWebContainerView;
import org.ops4j.pax.web.service.whiteboard.ErrorPageMapping;
import org.osgi.framework.wiring.BundleWiring;

/**
 * @author Alin Dreghiciu
 * @since 0.3.0, January 12, 2008
 */
public class ErrorPageModel extends ElementModel<ErrorPageMapping, ErrorPageEventData> {

	private static final Pattern ERROR_CODE = Pattern.compile("^\\d{3}$");

	/** Fully qualified class name(s) of the error and/or error code(s). */
	private final String[] errorPages;

	/** Request path of the error handler. Starts with a "/". */
	private String location;

	private boolean xx4 = false;
	private boolean xx5 = false;
	private final List<Integer> errorCodes = new ArrayList<>();
	private final List<String> exceptionClassNames = new ArrayList<>();

	/**
	 * Constructor used for unregistration purposes.
	 *
	 * @param errorPages
	 */
	public ErrorPageModel(String[] errorPages) {
		this.errorPages = errorPages;
	}

	public ErrorPageModel(final String[] errorPages, final String location) {
		if (!location.startsWith("/")) {
			throw new IllegalArgumentException(
					"Location must start with a slash (/)");
		}
		this.errorPages = errorPages;
		this.location = location;
	}

	@Override
	public void register(WhiteboardWebContainerView view) {
		view.registerErrorPages(this);
	}

	@Override
	public void unregister(WhiteboardWebContainerView view) {
		view.unregisterErrorPages(this);
	}

	@Override
	public ErrorPageEventData asEventData() {
		ErrorPageEventData data = new ErrorPageEventData(Arrays.copyOf(errorPages, errorPages.length), location);
		setCommonEventProperties(data);
		return data;
	}

	public String[] getErrorPages() {
		return errorPages;
	}

	public String getLocation() {
		return location;
	}

	public void setLocation(String location) {
		this.location = location;
	}

	@Override
	public String toString() {
		return "ErrorPageModel{id=" + getId()
				+ ",errorPages=" + Arrays.asList(errorPages)
				+ ",location=" + location
				+ "}";
	}

	@Override
	public Boolean performValidation() {
		for (String page : errorPages) {
			if ("4xx".equals(page)) {
				xx4 = true;
				continue;
			}
			if ("5xx".equals(page)) {
				xx5 = true;
				continue;
			}
			if (ERROR_CODE.matcher(page).matches()) {
				int code = Integer.parseInt(page);
				if (code < 400 || code > 599) {
					throw new IllegalArgumentException("HTTP error code should be between 400 and 599");
				}
				errorCodes.add(code);
				continue;
			}
			// should be FQCN of the Exception class - resolvable using this element's bundle
			ClassLoader loader = null;
			try {
				loader = getRegisteringBundle() == null
						? null : getRegisteringBundle().adapt(BundleWiring.class).getClassLoader();
				if (loader == null) {
					throw new IllegalArgumentException("Can't verify class name of error page \""
							+ page + "\" - no bundle associated with error pages");
				}
				Class<?> exClass = loader.loadClass(page);
				if (!Throwable.class.isAssignableFrom(exClass)) {
					throw new IllegalArgumentException("Can't use \"" + page + "\" as error page - this class"
							+ " doesn't inherit from java.lang.Throwable");
				}
				exceptionClassNames.add(exClass.getName());
			} catch (ClassNotFoundException e) {
				throw new IllegalArgumentException("Can't load \"" + page + "\" class using " + loader);
			}
		}

		return Boolean.TRUE;
	}

	public boolean isXx4() {
		return xx4;
	}

	public boolean isXx5() {
		return xx5;
	}

	public List<Integer> getErrorCodes() {
		return errorCodes;
	}

	public List<String> getExceptionClassNames() {
		return exceptionClassNames;
	}

}
