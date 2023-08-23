package org.jenkinsci.plugins.gwt;

import static com.google.common.base.Strings.isNullOrEmpty;
import static com.google.common.base.Strings.nullToEmpty;
import static java.util.logging.Level.FINE;
import static java.util.regex.Pattern.compile;
import static org.jenkinsci.plugins.gwt.resolvers.FlattenerUtils.filter;

import com.google.common.annotations.VisibleForTesting;
import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.jayway.jsonpath.JsonPath;
import com.jayway.jsonpath.PathNotFoundException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.logging.Logger;

public class Renderer {
  private static Logger LOGGER = Logger.getLogger(Renderer.class.getName());

  @VisibleForTesting
  public static boolean isMatching(
      final String renderedRegexpFilterText, final String regexpFilterExpression) {
    final boolean noFilterConfigured =
        isNullOrEmpty(renderedRegexpFilterText) && isNullOrEmpty(regexpFilterExpression);
    if (noFilterConfigured) {
      return true;
    }
    final boolean isMatching =
        compile(nullToEmpty(regexpFilterExpression)) //
            .matcher(nullToEmpty(renderedRegexpFilterText)) //
            .find();
    if (!isMatching) {
      LOGGER.log(
          FINE,
          "Not triggering \""
              + regexpFilterExpression
              + "\" not matching \""
              + renderedRegexpFilterText
              + "\".");
    }
    return isMatching;
  }

  @VisibleForTesting
  public static String renderText(
      String regexpFilterText, final Map<String, String> resolvedVariables) {
    if (isNullOrEmpty(regexpFilterText)) {
      return "";
    }
    final List<String> variables = getVariablesInResolveOrder(resolvedVariables.keySet());
    for (final String variable : variables) {
      regexpFilterText =
          replaceKey(regexpFilterText, resolvedVariables.get(variable), "$" + variable);
      regexpFilterText =
          replaceKey(regexpFilterText, resolvedVariables.get(variable), "${" + variable + "}");
    }
    return regexpFilterText;
  }

  private static String replaceKey(
      String regexpFilterText, final String resolvedVariable, final String key) {
    try {
      regexpFilterText =
          regexpFilterText //
              .replace(key, resolvedVariable);
    } catch (final IllegalArgumentException e) {
      throw new RuntimeException("Tried to replace " + key + " with " + resolvedVariable, e);
    }
    return regexpFilterText;
  }

  @VisibleForTesting
  static List<String> getVariablesInResolveOrder(final Set<String> unsorted) {
    final List<String> variables = new ArrayList<>(unsorted);
    Collections.sort(
        variables,
        new Comparator<String>() {
          @Override
          public int compare(final String o1, final String o2) {
            if (o1.length() == o2.length()) {
              return o1.compareTo(o2);
            } else if (o1.length() > o2.length()) {
              return -1;
            }
            return 1;
          }
        });
    return variables;
  }

  @VisibleForTesting
  public static List<String> render(String Path, final Map<String, String> resolvedVariables) {
    List<String> allPathList = new ArrayList<>();
    if (isNullOrEmpty(Path)) {
      return allPathList;
    }
    for (final Map.Entry<String, String> entry : resolvedVariables.entrySet()) {
      try {
        String fullName = "";
        String postContent = entry.getValue();
        List<String> newPathList = new ArrayList<>();
        String[] pathFieldList = Path.split("\\/");
        for (String pathField : pathFieldList) {
          final boolean isMatching = compile("^\\$\\{").matcher(nullToEmpty(pathField)).find();
          if (isMatching) {
            pathField = filter(pathField, "\\$\\{");
            pathField = filter(pathField, "\\}");
            Object content = JsonPath.read(postContent, pathField);
            if (!(content instanceof String)) {
              Gson GSON = new GsonBuilder().serializeNulls().create();
              content = GSON.toJson(content);
            }
            newPathList.add(content.toString());
            continue;
          }
          newPathList.add(pathField);
        }
        fullName = String.join("/", newPathList);
        fullName = fullName.replace("\"", "");
        allPathList.add(fullName);
      } catch (PathNotFoundException e) {
        LOGGER.log(FINE, "Spec job path not find");
      } catch (Exception e) {
        LOGGER.log(FINE, "render error: " + e.getMessage());
      }
    }
    return allPathList;
  }
}
