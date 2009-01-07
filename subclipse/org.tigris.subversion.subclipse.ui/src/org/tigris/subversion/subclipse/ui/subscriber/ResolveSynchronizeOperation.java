/*******************************************************************************
 * Copyright (c) 2005, 2006 Subclipse project and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     Subclipse project committers - initial API and implementation
 ******************************************************************************/
package org.tigris.subversion.subclipse.ui.subscriber;

import java.lang.reflect.InvocationTargetException;

import org.eclipse.compare.structuremergeviewer.IDiffElement;
import org.eclipse.core.resources.IContainer;
import org.eclipse.core.resources.IResource;
import org.eclipse.core.runtime.CoreException;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.jface.dialogs.ProgressMonitorDialog;
import org.eclipse.jface.operation.IRunnableWithProgress;
import org.eclipse.swt.custom.BusyIndicator;
import org.eclipse.swt.widgets.Display;
import org.eclipse.swt.widgets.Shell;
import org.eclipse.team.core.TeamException;
import org.eclipse.team.core.synchronize.SyncInfoSet;
import org.eclipse.team.ui.synchronize.ISynchronizePageConfiguration;
import org.eclipse.ui.actions.WorkspaceModifyOperation;
import org.tigris.subversion.subclipse.core.ISVNLocalResource;
import org.tigris.subversion.subclipse.core.SVNTeamProvider;
import org.tigris.subversion.subclipse.core.resources.SVNWorkspaceRoot;
import org.tigris.subversion.subclipse.ui.SVNUIPlugin;
import org.tigris.subversion.subclipse.ui.wizards.dialogs.SvnWizard;
import org.tigris.subversion.subclipse.ui.wizards.dialogs.SvnWizardDialog;
import org.tigris.subversion.subclipse.ui.wizards.dialogs.SvnWizardMarkResolvedPage;
import org.tigris.subversion.svnclientadapter.ISVNClientAdapter;
import org.tigris.subversion.svnclientadapter.ISVNConflictResolver;
import org.tigris.subversion.svnclientadapter.SVNClientException;

public class ResolveSynchronizeOperation extends SVNSynchronizeOperation {
	private boolean canceled;
	private int selectedResolution;
    
	public final static int PROGRESS_DIALOG = 1;
	public final static int PROGRESS_BUSYCURSOR = 2;

    public ResolveSynchronizeOperation(ISynchronizePageConfiguration configuration, IDiffElement[] elements) {
        super(configuration, elements);
    }

    protected boolean promptForConflictHandling(Shell shell, SyncInfoSet syncSet) {
        return true;
    }

    protected void run(SVNTeamProvider provider, SyncInfoSet set, IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
    	boolean folderSelected = false;  
    	canceled = false;
    	final IResource[] resources = set.getResources();
		for (int i = 0; i < resources.length; i++) {
			if (resources[i] instanceof IContainer) {
				folderSelected = true;
				break;
			}
		}
		if (folderSelected) {
			selectedResolution = ISVNConflictResolver.Choice.chooseMerged;
		} else {
			Display.getDefault().syncExec(new Runnable() {
				public void run() {
					SvnWizardMarkResolvedPage markResolvedPage = new SvnWizardMarkResolvedPage(resources);
					SvnWizard wizard = new SvnWizard(markResolvedPage);
			        SvnWizardDialog dialog = new SvnWizardDialog(getShell(), wizard);
			        wizard.setParentDialog(dialog);
			        if (dialog.open() == SvnWizardDialog.CANCEL) {
			        	canceled = true;
			        	return;
			        }
			        selectedResolution = markResolvedPage.getResolution();					
				}				
			});
		}
		if (canceled) return;
		run(new WorkspaceModifyOperation() {
			public void execute(IProgressMonitor monitor) throws InvocationTargetException {
				
				try {
					for (int i = 0; i < resources.length; i++) {						
                        ISVNLocalResource svnResource = SVNWorkspaceRoot.getSVNResourceFor(resources[i]);
                        ISVNClientAdapter svnClient = svnResource.getRepository().getSVNClient();
                        svnClient.resolve(resources[i].getLocation().toFile(), selectedResolution);
                        //for some reason, just refreshing the file won't cut it.
                        resources[i].getParent().refreshLocal(IResource.DEPTH_INFINITE, monitor);
					}
				} catch (TeamException e) {
					throw new InvocationTargetException(e);
				} catch (CoreException e) {
					throw new InvocationTargetException(e);
				} catch (SVNClientException e) {
					throw new InvocationTargetException(e);
				}
			}
		}, false /* cancelable */, PROGRESS_BUSYCURSOR);        
    }
    
	final protected void run(final IRunnableWithProgress runnable, boolean cancelable, int progressKind) throws InvocationTargetException, InterruptedException {
		final Exception[] exceptions = new Exception[] {null};
		
		// Ensure that no repository view refresh happens until after the action
		final IRunnableWithProgress innerRunnable = new IRunnableWithProgress() {
			public void run(IProgressMonitor monitor) throws InvocationTargetException, InterruptedException {
                SVNUIPlugin.getPlugin().getRepositoryManager().run(runnable, monitor);
			}
		};
		
		switch (progressKind) {
			case PROGRESS_BUSYCURSOR :
				BusyIndicator.showWhile(Display.getCurrent(), new Runnable() {
					public void run() {
						try {
							innerRunnable.run(new NullProgressMonitor());
						} catch (InvocationTargetException e) {
							exceptions[0] = e;
						} catch (InterruptedException e) {
							exceptions[0] = e;
						}
					}
				});
				break;
			case PROGRESS_DIALOG :
			default :
				new ProgressMonitorDialog(getShell()).run(true, cancelable,/*cancelable, true, */innerRunnable);	
				break;
		}
		if (exceptions[0] != null) {
			if (exceptions[0] instanceof InvocationTargetException)
				throw (InvocationTargetException)exceptions[0];
			else
				throw (InterruptedException)exceptions[0];
		}
	}       

}
