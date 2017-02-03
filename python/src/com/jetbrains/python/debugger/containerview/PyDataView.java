/*
 * Copyright 2000-2017 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.jetbrains.python.debugger.containerview;

import com.intellij.execution.process.ProcessHandler;
import com.intellij.icons.AllIcons;
import com.intellij.openapi.actionSystem.*;
import com.intellij.openapi.application.ApplicationManager;
import com.intellij.openapi.components.ServiceManager;
import com.intellij.openapi.diagnostic.Logger;
import com.intellij.openapi.project.DumbAware;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.util.Comparing;
import com.intellij.openapi.wm.ToolWindow;
import com.intellij.openapi.wm.ToolWindowManager;
import com.intellij.openapi.wm.ToolWindowType;
import com.intellij.openapi.wm.ex.ToolWindowManagerAdapter;
import com.intellij.openapi.wm.ex.ToolWindowManagerEx;
import com.intellij.ui.content.Content;
import com.intellij.ui.content.ContentFactory;
import com.intellij.ui.tabs.TabInfo;
import com.intellij.ui.tabs.impl.JBEditorTabs;
import com.intellij.xdebugger.XDebuggerManager;
import com.jetbrains.python.debugger.PyDebugProcess;
import com.jetbrains.python.debugger.PyDebugValue;
import com.jetbrains.python.debugger.PyFrameAccessor;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;
import java.util.stream.Collectors;

public class PyDataView implements DumbAware {
  public static final String DATA_VIEWER_ID = "Data View";
  public static final String COLORED_BY_DEFAULT = "python.debugger.dataview.coloredbydefault";
  public static final String EMPTY_TAB_NAME = "empty";
  private static final Logger LOG = Logger.getInstance(PyDataView.class);

  @NotNull private final Project myProject;
  private JBEditorTabs myTabs;

  public PyDataView(@NotNull Project project) {
    myProject = project;
  }

  public void show(@NotNull PyDebugValue value) {
    ToolWindow window = ToolWindowManager.getInstance(myProject).getToolWindow(DATA_VIEWER_ID);
    if (window == null) {
      LOG.error("Tool window '" + DATA_VIEWER_ID + "' is not found");
      return;
    }
    window.getContentManager().getReady(this).doWhenDone(() -> {
      TabInfo selectedInfo = addTab(value.getFrameAccessor());
      PyDataViewerPanel dataViewerPanel = (PyDataViewerPanel)selectedInfo.getComponent();
      dataViewerPanel.apply(value);
    });
    window.show(null);
  }

  public void closeRelatedTabs(@NotNull PyDebugProcess process) {
    for (TabInfo info : myTabs.getTabs()) {
      PyFrameAccessor frameAccessor = getPanel(info).getFrameAccessor();
      if (frameAccessor instanceof PyDebugProcess) {
        if (frameAccessor == process) {
          ApplicationManager.getApplication().invokeLater(() -> myTabs.removeTab(info));
        }
      }
    }
  }

  public void updateTabs(@NotNull ProcessHandler handler) {
    for (TabInfo info : myTabs.getTabs()) {
      PyDataViewerPanel panel = getPanel(info);
      PyFrameAccessor accessor = panel.getFrameAccessor();
      if (!(accessor instanceof PyDebugProcess)) {
        continue;
      }
      boolean shouldBeShown = Comparing.equal(handler, ((PyDebugProcess)accessor).getProcessHandler());
      info.setHidden(!shouldBeShown);
    }
    if (myTabs.getSelectedInfo() == null) {
      PyFrameAccessor accessor = getFrameAccessor(handler);
      if (accessor != null) {
        addTab(accessor);
      }
    }
  }

  @Nullable
  private PyFrameAccessor getFrameAccessor(@NotNull ProcessHandler handler) {
    for (PyDebugProcess process : XDebuggerManager.getInstance(myProject).getDebugProcesses(PyDebugProcess.class)) {
      if (Comparing.equal(handler, process.getProcessHandler())) {
        return process;
      }
    }
    return null;
  }

  public static PyDataView getInstance(@NotNull final Project project) {
    return ServiceManager.getService(project, PyDataView.class);
  }

  public void init(@NotNull ToolWindow toolWindow, @NotNull PyFrameAccessor frameAccessor) {
    myTabs = new PyDataViewTabs(myProject);
    myTabs.setPopupGroup(new DefaultActionGroup(new ColoredAction()), ActionPlaces.UNKNOWN, true);
    myTabs.setTabDraggingEnabled(true);
    final Content content = ContentFactory.SERVICE.getInstance().createContent(myTabs, "", false);
    content.setCloseable(true);
    toolWindow.getContentManager().addContent(content);
    addTab(frameAccessor);
    ((ToolWindowManagerEx)ToolWindowManager.getInstance(myProject)).addToolWindowManagerListener(new ToolWindowManagerAdapter() {
      @Override
      public void stateChanged() {
        ToolWindow window = ToolWindowManager.getInstance(myProject).getToolWindow(DATA_VIEWER_ID);
        if (window == null) {
          return;
        }
        if (toolWindow.isAvailable() && toolWindow.getType().equals(ToolWindowType.FLOATING) && !toolWindow.isVisible()) {
          toolWindow.setShowStripeButton(false);
          myTabs.removeAllTabs();
        }
      }
    });
  }

  private TabInfo addTab(@NotNull PyFrameAccessor frameAccessor) {
    if (hasOnlyEmptyTab()) {
      myTabs.removeTab(myTabs.getSelectedInfo());
    }
    PyDataViewerPanel panel = new PyDataViewerPanel(myProject, frameAccessor);
    TabInfo info = new TabInfo(panel);
    info.setText(EMPTY_TAB_NAME);
    info.setPreferredFocusableComponent(panel.getSliceTextField());
    info.setActions(new DefaultActionGroup(new NewViewerAction(frameAccessor)), ActionPlaces.UNKNOWN);
    info.setTabLabelActions(new DefaultActionGroup(new CloseViewerAction(info, frameAccessor)), ActionPlaces.UNKNOWN);
    panel.addListener(name -> info.setText(name));
    myTabs.addTab(info);
    myTabs.select(info, true);
    return info;
  }

  private boolean hasOnlyEmptyTab() {
    if (getVisibleTabs().size() != 1) {
      return false;
    }
    TabInfo info = myTabs.getSelectedInfo();
    if (info == null) {
      return false;
    }
    return getPanel(info).getSliceTextField().getText().isEmpty();
  }

  private List<TabInfo> getVisibleTabs() {
    return myTabs.getTabs().stream().filter(tabInfo -> !tabInfo.isHidden()).collect(Collectors.toList());
  }


  private class NewViewerAction extends AnAction {
    private final PyFrameAccessor myFrameAccessor;

    public NewViewerAction(PyFrameAccessor frameAccessor) {
      super("View New Container", "Open new container viewer", AllIcons.General.Add);
      myFrameAccessor = frameAccessor;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      addTab(myFrameAccessor);
    }
  }


  private class CloseViewerAction extends AnAction {
    private final TabInfo myInfo;
    private final PyFrameAccessor myFrameAccessor;

    public CloseViewerAction(TabInfo info, PyFrameAccessor frameAccessor) {
      super("Close Viewer", "Close selected viewer", AllIcons.Actions.Close);
      myInfo = info;
      myFrameAccessor = frameAccessor;
    }

    @Override
    public void actionPerformed(AnActionEvent e) {
      myTabs.removeTab(myInfo);
      if (getVisibleTabs().isEmpty()) {
        addTab(myFrameAccessor);
      }
    }
  }

  private class ColoredAction extends ToggleAction {
    public ColoredAction() {
      super("Colored");
    }

    @Override
    public boolean isSelected(AnActionEvent e) {
      PyDataViewerPanel panel = getPanel();
      if (panel == null) {
        return true;
      }
      return panel.isColored();
    }

    @Nullable
    private PyDataViewerPanel getPanel() {
      TabInfo info = myTabs.getSelectedInfo();
      if (info == null) {
        return null;
      }
      return PyDataView.getPanel(info);
    }

    @Override
    public void setSelected(AnActionEvent e, boolean state) {
      PyDataViewerPanel panel = getPanel();
      if (panel != null) {
        panel.setColored(state);
      }
    }
  }

  private static PyDataViewerPanel getPanel(TabInfo tabInfo) {
    return ((PyDataViewerPanel)tabInfo.getComponent());
  }
}
