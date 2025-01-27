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
package org.ops4j.pax.web.itest.container.httpservice;

import org.junit.Before;
import org.junit.Test;
import org.ops4j.pax.web.itest.container.AbstractContainerTestBase;
import org.ops4j.pax.web.itest.utils.client.HttpTestClientFactory;
import org.osgi.framework.Bundle;

/**
 * @author Toni Menzel (tonit)
 * @since Mar 3, 2009
 */
public abstract class AbstractWebConsoleIntegrationTest extends AbstractContainerTestBase {

	@Before
	public void setUp() throws Exception {
		configureAndWaitForServletWithMapping("/system/console/res/*", () -> {
			context.installBundle("mvn:commons-fileupload/commons-fileupload/1.4");
			context.installBundle("mvn:commons-io/commons-io/2.8.0");
			Bundle b = context.installBundle("mvn:org.apache.felix/org.apache.felix.webconsole/4.5.4");
			b.start();
		});
	}

	@Test
	public void testBundlesPath() throws Exception {
		HttpTestClientFactory.createDefaultTestClient()
				.withReturnCode(401)
				.doGETandExecuteTest("http://localhost:8181/system/console/bundles");

		HttpTestClientFactory.createDefaultTestClient()
				.authenticate("admin", "admin", "OSGi Management Console")
				.withResponseAssertion("Response must contain 'Apache Felix Web Console<br/>Bundles'",
						resp -> resp.contains("Apache Felix Web Console<br/>Bundles"))
				.doGETandExecuteTest("http://localhost:8181/system/console/bundles");
	}

}
