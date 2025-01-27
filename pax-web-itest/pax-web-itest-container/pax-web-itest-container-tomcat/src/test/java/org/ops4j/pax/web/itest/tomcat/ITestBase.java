/*
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
package org.ops4j.pax.web.itest.tomcat;

import javax.inject.Inject;

import org.apache.catalina.Globals;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.spi.reactors.ExamReactorStrategy;
import org.ops4j.pax.exam.spi.reactors.PerClass;
import org.ops4j.pax.web.itest.base.AbstractControlledTestBase;
import org.osgi.framework.BundleContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.ops4j.pax.exam.CoreOptions.*;
import static org.ops4j.pax.exam.OptionUtils.combine;

@ExamReactorStrategy(PerClass.class)
public class ITestBase extends AbstractControlledTestBase {

	private static final Logger LOG = LoggerFactory.getLogger(ITestBase.class);

	@Inject
	protected BundleContext bundleContext;

	@Override
	protected BundleContext getBundleContext() {
		return bundleContext;
	}

	public static Option[] configureBaseWithServlet() {
		return combine(
				baseConfigure(),
				systemPackages("javax.xml.namespace;version=1.0.0", "javax.transaction;version=1.1.0"),
				systemProperty("javax.servlet.context.tempdir").value("target"),
				systemProperty(Globals.CATALINA_BASE_PROP).value("target"));
	}

}
