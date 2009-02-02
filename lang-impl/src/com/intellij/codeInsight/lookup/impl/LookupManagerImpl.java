package com.intellij.codeInsight.lookup.impl;

import com.intellij.codeInsight.CodeInsightSettings;
import com.intellij.codeInsight.completion.impl.CamelHumpMatcher;
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer;
import com.intellij.codeInsight.documentation.DocumentationManager;
import com.intellij.codeInsight.hint.EditorHintListener;
import com.intellij.codeInsight.hint.HintManagerImpl;
import com.intellij.codeInsight.lookup.*;
import com.intellij.openapi.components.ProjectComponent;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.editor.EditorFactory;
import com.intellij.openapi.editor.event.EditorFactoryAdapter;
import com.intellij.openapi.editor.event.EditorFactoryEvent;
import com.intellij.openapi.project.Project;
import com.intellij.psi.PsiDocumentManager;
import com.intellij.psi.PsiFile;
import com.intellij.ui.LightweightHint;
import com.intellij.util.Alarm;
import com.intellij.util.messages.MessageBus;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.beans.PropertyChangeListener;
import java.beans.PropertyChangeSupport;

public class LookupManagerImpl extends LookupManager implements ProjectComponent {
  private final Project myProject;

  protected LookupImpl myActiveLookup = null;
  protected Editor myActiveLookupEditor = null;
  private final PropertyChangeSupport myPropertyChangeSupport = new PropertyChangeSupport(this);

  private boolean myIsDisposed;
  private EditorFactoryAdapter myEditorFactoryListener;

  public LookupManagerImpl(Project project, MessageBus bus) {
    myProject = project;

    bus.connect().subscribe(EditorHintListener.TOPIC, new EditorHintListener() {
      public void hintShown(final Project project, final LightweightHint hint, final int flags) {
        if (project == myProject) {
          Lookup lookup = getActiveLookup();
          if (lookup != null && (flags & HintManagerImpl.HIDE_BY_LOOKUP_ITEM_CHANGE) != 0) {
            lookup.addLookupListener(
              new LookupAdapter() {
                public void currentItemChanged(LookupEvent event) {
                  hint.hide();
                }

                public void itemSelected(LookupEvent event) {
                  hint.hide();
                }

                public void lookupCanceled(LookupEvent event) {
                  hint.hide();
                }
              }
            );
          }
        }
      }
    });

  }

  @NotNull
  public String getComponentName(){
    return "LookupManager";
  }

  public void initComponent() { }

  public void disposeComponent(){
  }

  public void projectOpened(){
    myEditorFactoryListener = new EditorFactoryAdapter() {
      public void editorReleased(EditorFactoryEvent event) {
        if (event.getEditor() == myActiveLookupEditor){
          hideActiveLookup();
        }
      }
    };
    EditorFactory.getInstance().addEditorFactoryListener(myEditorFactoryListener);
  }

  public void projectClosed(){
    EditorFactory.getInstance().removeEditorFactoryListener(myEditorFactoryListener);
    myIsDisposed = true;
  }

  public Lookup showLookup(final Editor editor, LookupElement[] items, LookupItemPreferencePolicy itemPreferencePolicy) {
    return showLookup(editor, items, "", itemPreferencePolicy);
  }

  public Lookup showLookup(Editor editor,
                           LookupElement[] items,
                           LookupItemPreferencePolicy itemPreferencePolicy,
                           @Nullable String bottomText) {
    return showLookup(editor, items, itemPreferencePolicy);
  }

  public Lookup showLookup(final Editor editor, final LookupElement[] items, final String prefix, final LookupItemPreferencePolicy itemPreferencePolicy) {
    final LookupImpl lookup = createLookup(editor, items, prefix, itemPreferencePolicy);
    lookup.show();
    return lookup;
  }

  public Lookup showLookup(Editor editor,
                           LookupElement[] items,
                           String prefix,
                           LookupItemPreferencePolicy itemPreferencePolicy,
                           @Nullable String bottomText) {
    return showLookup(editor, items, prefix, itemPreferencePolicy);
  }

  public LookupImpl createLookup(final Editor editor, final LookupElement[] items, final String prefix, final LookupItemPreferencePolicy itemPreferencePolicy) {
    return createLookup(editor, items, prefix, itemPreferencePolicy, null);
  }

  public LookupImpl createLookup(final Editor editor, LookupElement[] items, String prefix, LookupItemPreferencePolicy itemPreferencePolicy, @Nullable String bottomText) {
    hideActiveLookup();

    final CodeInsightSettings settings = CodeInsightSettings.getInstance();

    final PsiFile psiFile = PsiDocumentManager.getInstance(myProject).getPsiFile(editor.getDocument());

    final Alarm alarm = new Alarm();
    final Runnable request = new Runnable(){
      public void run() {
        DocumentationManager.getInstance(myProject).showJavaDocInfo(editor, psiFile, false);
      }
    };
    if (settings.AUTO_POPUP_JAVADOC_INFO){
      alarm.addRequest(request, settings.JAVADOC_INFO_DELAY);
    }

    final DaemonCodeAnalyzer daemonCodeAnalyzer = DaemonCodeAnalyzer.getInstance(myProject);
    if (daemonCodeAnalyzer != null) {
      daemonCodeAnalyzer.setUpdateByTimerEnabled(false);
    }
    for (final LookupElement item : items) {
      item.setPrefixMatcher(new CamelHumpMatcher(prefix));
    }
    myActiveLookup = new LookupImpl(myProject, editor, items, itemPreferencePolicy);
    myActiveLookupEditor = editor;
    myActiveLookup.addLookupListener(
      new LookupAdapter(){
        public void itemSelected(LookupEvent event) {
          dispose();
        }

        public void lookupCanceled(LookupEvent event) {
          dispose();
        }

        public void currentItemChanged(LookupEvent event) {
          alarm.cancelAllRequests();
          if (settings.AUTO_POPUP_JAVADOC_INFO){
            alarm.addRequest(request, settings.JAVADOC_INFO_DELAY);
          }
        }

        private void dispose(){
          alarm.cancelAllRequests();
          if (daemonCodeAnalyzer != null) {
            daemonCodeAnalyzer.setUpdateByTimerEnabled(true);
          }
          if (myActiveLookup == null) return;
          myActiveLookup.removeLookupListener(this);
          Lookup lookup = myActiveLookup;
          myActiveLookup = null;
          myActiveLookupEditor = null;
          myPropertyChangeSupport.firePropertyChange(PROP_ACTIVE_LOOKUP, lookup, null);
        }
      }
    );
    myPropertyChangeSupport.firePropertyChange(PROP_ACTIVE_LOOKUP, null, myActiveLookup);
    return myActiveLookup;
  }

  public void hideActiveLookup() {
    if (myActiveLookup != null){
      myActiveLookup.hide();
    }
  }

  public Lookup getActiveLookup() {
    return myActiveLookup;
  }

  public void addPropertyChangeListener(PropertyChangeListener listener) {
    myPropertyChangeSupport.addPropertyChangeListener(listener);
  }

  public void removePropertyChangeListener(PropertyChangeListener listener) {
    myPropertyChangeSupport.removePropertyChangeListener(listener);
  }

  public boolean isDisposed() {
    return myIsDisposed;
  }

}
