package com.jetbrains.python.codeInsight.intentions;

import com.intellij.codeInsight.intention.IntentionAction;
import com.intellij.lang.Language;
import com.intellij.openapi.editor.Editor;
import com.intellij.openapi.project.Project;
import com.intellij.openapi.ui.MessageType;
import com.intellij.psi.PsiElement;
import com.intellij.psi.PsiFile;
import com.intellij.psi.PsiReference;
import com.intellij.psi.search.PsiElementProcessor;
import com.intellij.psi.util.PsiTreeUtil;
import com.intellij.util.ArrayUtil;
import com.intellij.util.IncorrectOperationException;
import com.intellij.util.containers.HashSet;
import com.jetbrains.python.PyBundle;
import com.jetbrains.python.PythonLanguage;
import com.jetbrains.python.psi.*;
import static com.jetbrains.python.psi.PyUtil.sure;
import com.jetbrains.python.psi.resolve.PyResolveUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Set;

/**
 * Converts {@code import foo} to {@code from foo import names} or {@code from ... import module} to {@code from ...module import names}.
 * Module names used as qualifiers are removed.
 * <br>
 * <i>NOTE: currently we only check usage of module name in the same file. For re-exported module names this is not sufficient.</i>
 * <br>
 * <small>User: dcheryasov
 * Date: Sep 22, 2009 1:42:52 AM</small>
 */
public class ImportToImportFromIntention implements IntentionAction {

  private String myModuleName = null;
  private String myQualifierName = null;
  private PsiElement myReferee = null;
  private PyImportElement myImportElement = null;
  private Collection<PsiReference> myReferences = null;
  private boolean myHasModuleReference = false; // is anything that resolves to our imported module is just an exact reference to that module
  private int myRelativeLevel; // true if "from ... import"

  @NotNull
  public String getText() {
    String module_name = "?";
    if (myImportElement != null) {
      PyReferenceExpression reference = myImportElement.getImportReference();
      if (reference != null) module_name = PyResolveUtil.toPath(reference, ".");
    }
    return PyBundle.message("INTN.convert.to.from.$0.import.$1", getDots()+module_name, "...");
  }

  @NotNull
  public String getFamilyName() {
    return PyBundle.message("INTN.Family.convert.import.unqualify");
  }

  private String getDots() {
    String dots = "";
    for (int i=0; i<myRelativeLevel; i+=1) dots += "."; // this generally runs 1-2 times, so it's cheaper than allocating a StringBuilder
    return dots;
  }

  @Nullable
  private static PyImportElement findImportElement(Editor editor, PsiFile file) {
    PyImportElement import_elt = PsiTreeUtil.getParentOfType(file.findElementAt(editor.getCaretModel().getOffset()), PyImportElement.class);
    if (import_elt != null && import_elt.isValid()) return import_elt;
    else return null;
  }

  public boolean isAvailable(@NotNull Project project, Editor editor, PsiFile file) {
    boolean available = false;
    myImportElement = findImportElement(editor, file);
    if (myImportElement != null) {
      final PsiElement parent = myImportElement.getParent();
      if (parent instanceof PyImportStatement) {
        myRelativeLevel = 0;
        available = true;
      }
      else if (parent instanceof PyFromImportStatement) {
        PyFromImportStatement from_import = (PyFromImportStatement)parent;
        final int relative_level = from_import.getRelativeLevel();
        if (from_import.isValid() && relative_level > 0 && from_import.getImportSource() == null) {
          myRelativeLevel = relative_level;
          available = true;
        }
      }
    }
    if (available) {
      collectReferencesAndOtherData(file); // this will cache data for the invocation
      available = myReferences != null && myReferences.size() > 0;
    }
    return available;
  }


  private static String[] EMPTY_STRINGS = ArrayUtil.EMPTY_STRING_ARRAY;

  private void collectReferencesAndOtherData(PsiFile file) {
    //PyImportElement myImportElement = findImportElement(editor, file);
    assert myImportElement != null : "isAvailable() must have returned true, but myImportElement is null";

    // usages of imported name are qualifiers; what they refer to?
    PyReferenceExpression reference = myImportElement.getImportReference();
    if (reference != null) {
      myModuleName = PyResolveUtil.toPath(reference, ".");
      myQualifierName = myImportElement.getVisibleName(); 
      myReferee = reference.resolve();
      myHasModuleReference = false;
      if (myReferee != null && myModuleName != null && myQualifierName != null) {
        final Collection<PsiReference> references = new ArrayList<PsiReference>();
        PsiTreeUtil.processElements(file, new PsiElementProcessor() {
          public boolean execute(PsiElement element) {
            if (element instanceof PyReferenceExpression && PsiTreeUtil.getParentOfType(element, PyImportElement.class) == null) {
              PyReferenceExpression ref = (PyReferenceExpression)element;
              if (myQualifierName.equals(PyResolveUtil.toPath(ref, "."))) {  // filter out other names that might resolve to our target
                PsiElement elt = ref.getElement();
                PsiElement parent_elt = elt.getParent();
                if (parent_elt instanceof PyQualifiedExpression) { // really qualified by us, not just referencing?
                  PsiElement resolved = ref.resolve();
                  if (resolved == myReferee) references.add(ref);
                }
                else myHasModuleReference = true;
              }
            }
            return true;
          }
        });
        myReferences = references;
      }
    }
  }

  public void invoke(@NotNull Project project, Editor editor, PsiFile file) throws IncorrectOperationException {
    assert myImportElement != null : "isAvailable() must have returned true, but myImportElement is null";
    PyUtil.sure(myImportElement.getImportReference());

    // usages of imported name are qualifiers; what they refer to?
    try {
      if (myReferences == null) collectReferencesAndOtherData(file); // just in case
      // remember names and make them drop qualifiers
      Set<String> used_names = new HashSet<String>();
      for (PsiReference ref : myReferences) {
        PsiElement elt = ref.getElement();
        PsiElement parent_elt = elt.getParent();
        used_names.add(sure(PyUtil.sure(parent_elt).getLastChild()).getText()); // TODO: find ident node more properly
        PyUtil.ensureWritable(elt);
        PsiElement next_elt = elt.getNextSibling();
        if (next_elt != null && ".".equals(next_elt.getText())) next_elt.delete();
        elt.delete();
      }

      // create a separate import stmt for the module
      PsiElement importer = myImportElement.getParent();
      PyStatement import_statement;
      PyImportElement[] import_elements;
      if (importer instanceof PyImportStatement) {
        import_statement = (PyImportStatement)importer;
        import_elements = ((PyImportStatement)import_statement).getImportElements();
      }
      else if (importer instanceof PyFromImportStatement) {
        import_statement = (PyFromImportStatement)importer;
        import_elements = ((PyFromImportStatement)import_statement).getImportElements();
      }
      else throw new IncorrectOperationException("Not an import at all");
      Language language = myImportElement.getLanguage();
      assert language instanceof PythonLanguage;
      PythonLanguage pythonLanguage = (PythonLanguage)language;
      PyElementGenerator generator = pythonLanguage.getElementGenerator();
      StringBuilder builder = new StringBuilder("from ").append(getDots()).append(myModuleName).append(" import ");
      PyUtil.joinArray(used_names.toArray(EMPTY_STRINGS), ", ", builder);
      PyFromImportStatement from_import_stmt = generator.createFromText(project, PyFromImportStatement.class,  builder.toString());
      PsiElement parent = import_statement.getParent();
      sure(parent);  sure(parent.isValid());
      if (import_elements.length == 1) {
        if (myHasModuleReference) parent.addAfter(from_import_stmt, import_statement); // add 'import from': we need the module imported as is
        else { // replace entire existing import
          sure(parent.getNode()).replaceChild(sure(import_statement.getNode()), sure(from_import_stmt.getNode()));
          // import_statement.replace(from_import_stmt);
        }
      }
      else  {
        if (! myHasModuleReference) {
          // cut the module out of import, add a from-import.
          for (PyImportElement pie : import_elements) {
            if (pie == myImportElement) {
              PyUtil.removeListNode(pie);
              break;
            }
          }
        }
        parent.addAfter(from_import_stmt, import_statement);
      }
    }
    catch (IncorrectOperationException ignored) {
      PyUtil.showBalloon(project, PyBundle.message("QFIX.action.failed"), MessageType.WARNING);
    }
  }

  public boolean startInWriteAction() {
    return true;
  }
}
