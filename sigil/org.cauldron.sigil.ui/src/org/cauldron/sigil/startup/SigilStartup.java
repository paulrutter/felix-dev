/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 * 
 *   http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */

package org.cauldron.sigil.startup;

import org.cauldron.sigil.SigilCore;
import org.cauldron.sigil.job.ResolveProjectsJob;
import org.cauldron.sigil.repository.IRepositoryChangeListener;
import org.cauldron.sigil.repository.RepositoryChangeEvent;
import org.eclipse.core.resources.IWorkspace;
import org.eclipse.core.resources.ResourcesPlugin;
import org.eclipse.ui.IStartup;

public class SigilStartup implements IStartup {

	public void earlyStartup() {
		// Create a task to run the resolver
		final Runnable resolver = new Runnable() {
			public void run() {
				IWorkspace workspace = ResourcesPlugin.getWorkspace();
				ResolveProjectsJob job = new ResolveProjectsJob(workspace);
				job.setSystem(true);
				job.schedule();
			}
		};
		
		// Register a repository change listener to re-run the resolver when repository changes
		SigilCore.getGlobalRepositoryManager().addRepositoryChangeListener(new IRepositoryChangeListener() {
			public void repositoryChanged(RepositoryChangeEvent event) {
				resolver.run();
			}
		});

		// Run the resolver now
		resolver.run();
	}
}
