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

package org.cauldron.bld.core.repository;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.cauldron.bld.core.BldCore;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.SubMonitor;
import org.cauldron.sigil.model.ICompoundModelElement;
import org.cauldron.sigil.model.IModelElement;
import org.cauldron.sigil.model.eclipse.ILibrary;
import org.cauldron.sigil.model.eclipse.ILibraryImport;
import org.cauldron.sigil.model.eclipse.ISigilBundle;
import org.cauldron.sigil.model.osgi.IPackageExport;
import org.cauldron.sigil.model.osgi.IPackageImport;
import org.cauldron.sigil.model.osgi.IRequiredBundle;
import org.cauldron.sigil.repository.IBundleRepository;
import org.cauldron.sigil.repository.IBundleResolver;
import org.cauldron.sigil.repository.IRepositoryManager;
import org.cauldron.sigil.repository.IResolution;
import org.cauldron.sigil.repository.IResolutionMonitor;
import org.cauldron.sigil.repository.ResolutionConfig;
import org.cauldron.sigil.repository.ResolutionException;
import org.osgi.framework.Version;

public class BundleResolver implements IBundleResolver {

	private class BundleOrderComparator implements Comparator<ISigilBundle> {
		private IModelElement requirement;
		
		public BundleOrderComparator(IModelElement requirement) {
			this.requirement = requirement;
		}

		public int compare(ISigilBundle o1, ISigilBundle o2) {
			int c = compareVersions(o1, o2);
			
			if ( c == 0 ) {
				c = compareImports(o1, o2);
			}
			
			return c;
		}

		private int compareImports(ISigilBundle o1, ISigilBundle o2) {
			int c1 = o1.getBundleInfo().getImports().size();
			int c2 = o2.getBundleInfo().getImports().size();
			
			if ( c1 < c2 ) {
				return -1;
			}
			else if ( c2 > c1 ) {
				return 1;
			}
			else {
				return 0;
			}
		}

		private int compareVersions(ISigilBundle o1, ISigilBundle o2) {
			Version v1 = null;
			Version v2 = null;
			if ( requirement instanceof IPackageImport ) {
				v1 = findExportVersion( (IPackageImport) requirement, o1 );
				v2 = findExportVersion( (IPackageImport) requirement, o2 );
			}
			else if ( requirement instanceof IRequiredBundle ) {
				v1 = o1.getBundleInfo().getVersion();
				v2 = o1.getBundleInfo().getVersion();
			}
			
			if ( v1 == null ) {
				if ( v2 == null ) {
					return 0;
				}
				else {
					return 1;
				}
			}
			else {
				if ( v2 == null ) {
					return -1;
				}
				else {
					return v2.compareTo(v1);
				}
			}
		}

		private Version findExportVersion(IPackageImport pi, ISigilBundle o1) {
			for ( IPackageExport pe : o1.getBundleInfo().getExports() ) {
				if ( pi.getPackageName().equals( pi.getPackageName() ) ) {
					return pe.getVersion();
				}
			}
			
			return null;
		}

	}

	private class ResolutionContext {
		private final IModelElement root;
		private final ResolutionConfig config;
		private final IResolutionMonitor monitor;
		
		private final Resolution resolution = new Resolution();
		private final Set<IModelElement> parsed = new HashSet<IModelElement>();
		private final LinkedList<IModelElement> requirements = new LinkedList<IModelElement>();
		
		public ResolutionContext(IModelElement root, ResolutionConfig config, IResolutionMonitor monitor) {
			this.root = root;
			this.config = config;
			this.monitor = monitor;
		}

		public void enterModelElement(IModelElement element) {
			parsed.add(element);
		}
		
		public void exitModelElement(IModelElement element) {
			parsed.remove(element);
		}
		
		public boolean isNewModelElement(IModelElement element) {
			return !parsed.contains(element);
		}
		
		public boolean isValid() {
			return resolution.isSuccess();
		}

		public void setValid(boolean valid) {
			resolution.setSuccess(valid);
		}

		public ResolutionException newResolutionException() {
			return new ResolutionException(root, requirements.toArray( new IModelElement[requirements.size()]) );
		}

		public void startRequirement(IModelElement element) {
			requirements.add(element);
			monitor.startResolution(element);
		}
		
		public void endRequirement(IModelElement element) {
			ISigilBundle provider = resolution.getProvider(element);
			
			setValid( provider != null || isOptional(element) || config.isIgnoreErrors() );
		
			if ( isValid() ) {
				// only clear stack if valid
				// else use it as an aid to trace errors
				requirements.remove(element);
			}
			
			monitor.endResolution( element, provider );	
		}		
	}
	
	private class Resolution implements IResolution {
		private Map<ISigilBundle, List<IModelElement>> providees = new HashMap<ISigilBundle, List<IModelElement>>();
		private Map<IModelElement, ISigilBundle> providers = new HashMap<IModelElement, ISigilBundle>();
		private boolean success = true; // assume success
		
		boolean addProvider(IModelElement element, ISigilBundle provider) {
			providers.put( element, provider );
			
			List<IModelElement> requirements = providees.get( provider );
			
			boolean isNewProvider = requirements == null;
			
			if ( isNewProvider ) {
				requirements = new ArrayList<IModelElement>();
				providees.put( provider, requirements );
			}
			
			requirements.add( element );
			
			return isNewProvider;
		}
		
		void removeProvider(IModelElement element, ISigilBundle provider) {
			providers.remove(element);
			List<IModelElement> e = providees.get(provider);
			e.remove(element);
			if ( e.isEmpty() ) {
				providees.remove(provider);
			}
		}
		
		void setSuccess(boolean success) {
			this.success = success;
		}

		public boolean isSuccess() {
			return success;
		}
		
		public ISigilBundle getProvider(IModelElement requirement) {
			return providers.get(requirement);
		}
		
		public Set<ISigilBundle> getBundles() {
			return providees.keySet();
		}

		public List<IModelElement> getMatchedRequirements(ISigilBundle bundle) {
			return providees.get(bundle);
		}
		
		public boolean isSynchronized() {
			for ( ISigilBundle b : getBundles() ) {
				if ( !b.isSynchronized() ) {
					return false;
				}
			}
			
			return true;
		}
		
		public void synchronize(IProgressMonitor monitor) {
			Set<ISigilBundle> bundles = getBundles();
			SubMonitor progress = SubMonitor.convert(monitor, bundles.size());
			
			for ( ISigilBundle b : bundles ) {
				if ( monitor.isCanceled() ) {
					break;
				}
				
				try {
					b.synchronize(progress.newChild(1));
				} catch (IOException e) {
					BldCore.error( "Failed to synchronize " + b, e );
				}
			}
		}
	}

	private static final IResolutionMonitor NULL_MONITOR = new IResolutionMonitor() {
		public void endResolution(IModelElement requirement,
				ISigilBundle sigilBundle) {
		}

		public boolean isCanceled() {
			return false;
		}

		public void startResolution(IModelElement requirement) {
		}		
	};
	
	private IRepositoryManager repositoryManager;
	
	public BundleResolver(IRepositoryManager repositoryManager) {
		this.repositoryManager = repositoryManager;
	}

	public IResolution resolve(IModelElement element, ResolutionConfig config, IResolutionMonitor monitor) throws ResolutionException {
		if ( monitor == null ) {
			monitor = NULL_MONITOR;
		}
		ResolutionContext ctx = new ResolutionContext(element, config, monitor);

		resolveElement(element, ctx);
		
		if ( !ctx.isValid() ) {
			throw ctx.newResolutionException();
		}
		
		return ctx.resolution;
	}

	private void resolveElement(IModelElement element, ResolutionContext ctx) throws ResolutionException {
		if ( isRequirement(element) ) {
			resolveRequirement(element, ctx);
		}
		
		if ( ctx.isValid() && element instanceof ICompoundModelElement ) {
			resolveCompound((ICompoundModelElement) element, ctx);
		}
	}

	private void resolveCompound(ICompoundModelElement compound, ResolutionContext ctx) throws ResolutionException {
		for ( IModelElement element : compound.children() ) {
			if ( ctx.isNewModelElement(element) ) {
				if ( isRequirement(element) ) {
					resolveRequirement(element, ctx);
				}
				else if ( element instanceof ICompoundModelElement ) {
					if ( !ctx.monitor.isCanceled() ) {
						ctx.enterModelElement( element );
						resolveElement((ICompoundModelElement) element, ctx);	
						ctx.exitModelElement(element);
					}
				}

				if ( !ctx.isValid() ) {
					break;					
				}
			}
		}
	}

	private void resolveRequirement(IModelElement requirement, ResolutionContext ctx) throws ResolutionException {
		if ( ctx.config.isOptional() || !isOptional(requirement) ) {
			ctx.startRequirement(requirement );
	
			try {
				int[] priorities = repositoryManager.getPriorityLevels();
				
				outer: for ( int i = 0; i< priorities.length; i++ ) {
					List<ISigilBundle> providers = findProvidersAtPriority(priorities[i], requirement, ctx);
					
					if ( !providers.isEmpty() && !ctx.monitor.isCanceled() ) {
						if ( providers.size() > 1 ) {
							Collections.sort(providers, new BundleOrderComparator(requirement));
						}
		
						for ( ISigilBundle provider : providers ) {
							// reset validity - if there's another provider it can still be solved
							ctx.setValid(true);
							if ( ctx.resolution.addProvider(requirement, provider) ) {
								if ( ctx.config.isDependents() ) {
									resolveElement(provider, ctx);
								}
								
								if ( ctx.isValid() ) {
									break outer;
								}
								else {
									ctx.resolution.removeProvider(requirement, provider);
								}
							}
							else {
								break outer;
							}
						}
					}
				}
			}
			finally {
				ctx.endRequirement(requirement);
			}
		}
	}

	private List<ISigilBundle> findProvidersAtPriority(int i, IModelElement requirement, ResolutionContext ctx) throws ResolutionException {
		ArrayList<ISigilBundle> providers = new ArrayList<ISigilBundle>();
		
		for (IBundleRepository rep : repositoryManager.getRepositories(i)) {
			if ( ctx.monitor.isCanceled() ) {
				break;
			}
			providers.addAll( findProviders( requirement, ctx.config, rep ) );
		}
		
		return providers;
	}

	private Collection<ISigilBundle> findProviders(IModelElement requirement, ResolutionConfig config, IBundleRepository rep) throws ResolutionException {
		ArrayList<ISigilBundle> found = new ArrayList<ISigilBundle>();
		
		if ( requirement instanceof IPackageImport ) {
			IPackageImport pi = (IPackageImport) requirement;
			found.addAll( rep.findAllProviders( pi, config.getOptions() ) );
		}
		else if ( requirement instanceof IRequiredBundle ) {
			IRequiredBundle rb = (IRequiredBundle) requirement;
			found.addAll( rep.findAllProviders( rb, config.getOptions() ) );
		}
		else if ( requirement instanceof ILibraryImport ) {
			ILibrary lib = repositoryManager.resolveLibrary((ILibraryImport) requirement);
			if (lib != null) {
				found.addAll( rep.findProviders(lib, config.getOptions()) );
			}
		}
		else {
			// shouldn't get here - developer error if do
			// use isRequirement before getting anywhere near this logic...
			throw new IllegalStateException( "Invalid requirement type " + requirement );
		}

		return found;
	}
	

	private boolean isOptional(IModelElement element) {
		if ( element instanceof IPackageImport ) {
			return ((IPackageImport) element).isOptional();
		}
		else if ( element instanceof IRequiredBundle ) {
			return ((IRequiredBundle) element).isOptional();
		}
		else if ( element instanceof ILibraryImport ) {
			ILibrary lib = repositoryManager.resolveLibrary((ILibraryImport) element);
			for ( IPackageImport pi : lib.getImports() ) {
				if ( !isOptional(pi) ) {
					return false;
				}
			}
			return true;
		}
		else {
			// should never get this due to isRequirement test prior to calling this
			// developer error if found
			throw new IllegalStateException( "Invalid optional element test for " + element);
		}
	}

	private boolean isRequirement(IModelElement element) {
		return element instanceof IPackageImport || element instanceof IRequiredBundle || element instanceof ILibraryImport;
	}
	
	
}
