package com.google.javascript.jscomp.parsing;
import com.google.common.base.Preconditions;
import com.google.common.base.Splitter;
import com.google.common.collect.ImmutableSet;
import com.google.common.collect.Lists;
import com.google.common.collect.Sets;
import com.google.javascript.jscomp.parsing.Config.LanguageMode;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.JSDocInfo;
import com.google.javascript.rhino.JSDocInfo.Visibility;
import com.google.javascript.rhino.JSDocInfoBuilder;
import com.google.javascript.rhino.JSTypeExpression;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.ScriptRuntime;
import com.google.javascript.rhino.Token;
import com.google.javascript.rhino.head.ErrorReporter;
import com.google.javascript.rhino.head.ast.Comment;
import com.google.javascript.rhino.jstype.StaticSourceFile;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

final public class JsDocInfoParser  {
  final private JsDocTokenStream stream;
  final private JSDocInfoBuilder jsdocBuilder;
  final private StaticSourceFile sourceFile;
  final private Node associatedNode;
  final private ErrorReporter errorReporter;
  final private ErrorReporterParser parser = new ErrorReporterParser();
  final private Node templateNode;
  private JSDocInfo fileOverviewJSDocInfo = null;
  private State state;
  final private Map<String, Annotation> annotationNames;
  final private Set<String> suppressionNames;
  final private static Set<String> modifiesAnnotationKeywords = ImmutableSet.<String>of("this", "arguments");
  private Node.FileLevelJsDocBuilder fileLevelJsDocBuilder;
  final private static JsDocToken NO_UNREAD_TOKEN = null;
  private JsDocToken unreadToken = NO_UNREAD_TOKEN;
  JsDocInfoParser(JsDocTokenStream stream, Comment commentNode, Node associatedNode, Config config, ErrorReporter errorReporter) {
    super();
    this.stream = stream;
    this.associatedNode = associatedNode;
    this.sourceFile = associatedNode == null ? null : associatedNode.getStaticSourceFile();
    this.jsdocBuilder = new JSDocInfoBuilder(config.parseJsDocDocumentation);
    if(commentNode != null) {
      this.jsdocBuilder.recordOriginalCommentString(commentNode.getValue());
    }
    this.annotationNames = config.annotationNames;
    this.suppressionNames = config.suppressionNames;
    this.errorReporter = errorReporter;
    this.templateNode = this.createTemplateNode();
  }
  private ExtractionInfo extractBlockComment(JsDocToken token) {
    StringBuilder builder = new StringBuilder();
    boolean ignoreStar = true;
    do {
      switch (token){
        case ANNOTATION:
        case EOC:
        case EOF:
        return new ExtractionInfo(builder.toString().trim(), token);
        case STAR:
        if(!ignoreStar) {
          if(builder.length() > 0) {
            builder.append(' ');
          }
          builder.append('*');
        }
        token = next();
        continue ;
        case EOL:
        ignoreStar = true;
        builder.append('\n');
        token = next();
        continue ;
        default:
        if(!ignoreStar && builder.length() > 0) {
          builder.append(' ');
        }
        ignoreStar = false;
        builder.append(toString(token));
        String line = stream.getRemainingJSDocLine();
        line = trimEnd(line);
        builder.append(line);
        token = next();
      }
    }while(true);
  }
  private ExtractionInfo extractMultilineTextualBlock(JsDocToken token) {
    return extractMultilineTextualBlock(token, WhitespaceOption.SINGLE_LINE);
  }
  @SuppressWarnings(value = {"fallthrough", }) private ExtractionInfo extractMultilineTextualBlock(JsDocToken token, WhitespaceOption option) {
    if(token == JsDocToken.EOC || token == JsDocToken.EOL || token == JsDocToken.EOF) {
      return new ExtractionInfo("", token);
    }
    stream.update();
    int startLineno = stream.getLineno();
    int startCharno = stream.getCharno() + 1;
    String line = stream.getRemainingJSDocLine();
    if(option != WhitespaceOption.PRESERVE) {
      line = line.trim();
    }
    StringBuilder builder = new StringBuilder();
    builder.append(line);
    state = State.SEARCHING_ANNOTATION;
    token = next();
    boolean ignoreStar = false;
    int lineStartChar = -1;
    do {
      switch (token){
        case STAR:
        if(ignoreStar) {
          lineStartChar = stream.getCharno() + 1;
        }
        else {
          if(builder.length() > 0) {
            builder.append(' ');
          }
          builder.append('*');
        }
        token = next();
        continue ;
        case EOL:
        if(option != WhitespaceOption.SINGLE_LINE) {
          builder.append("\n");
        }
        ignoreStar = true;
        lineStartChar = 0;
        token = next();
        continue ;
        default:
        ignoreStar = false;
        state = State.SEARCHING_ANNOTATION;
        boolean isEOC = token == JsDocToken.EOC;
        if(!isEOC) {
          if(lineStartChar != -1 && option == WhitespaceOption.PRESERVE) {
            int numSpaces = stream.getCharno() - lineStartChar;
            for(int i = 0; i < numSpaces; i++) {
              builder.append(' ');
            }
            lineStartChar = -1;
          }
          else 
            if(builder.length() > 0) {
              builder.append(' ');
            }
        }
        if(token == JsDocToken.EOC || token == JsDocToken.EOF || (token == JsDocToken.ANNOTATION && option != WhitespaceOption.PRESERVE)) {
          String multilineText = builder.toString();
          if(option != WhitespaceOption.PRESERVE) {
            multilineText = multilineText.trim();
          }
          int endLineno = stream.getLineno();
          int endCharno = stream.getCharno();
          if(multilineText.length() > 0) {
            jsdocBuilder.markText(multilineText, startLineno, startCharno, endLineno, endCharno);
          }
          return new ExtractionInfo(multilineText, token);
        }
        builder.append(toString(token));
        line = stream.getRemainingJSDocLine();
        if(option != WhitespaceOption.PRESERVE) {
          line = trimEnd(line);
        }
        builder.append(line);
        token = next();
      }
    }while(true);
  }
  private ExtractionInfo extractSingleLineBlock() {
    stream.update();
    int lineno = stream.getLineno();
    int charno = stream.getCharno() + 1;
    String line = stream.getRemainingJSDocLine().trim();
    if(line.length() > 0) {
      jsdocBuilder.markText(line, lineno, charno, lineno, charno + line.length());
    }
    return new ExtractionInfo(line, next());
  }
  JSDocInfo getFileOverviewJSDocInfo() {
    return fileOverviewJSDocInfo;
  }
  JSDocInfo retrieveAndResetParsedJSDocInfo() {
    return jsdocBuilder.build(associatedNode);
  }
  private JSTypeExpression createJSTypeExpression(Node n) {
    return n == null ? null : new JSTypeExpression(n, getSourceName());
  }
  private JsDocToken current() {
    JsDocToken t = unreadToken;
    unreadToken = NO_UNREAD_TOKEN;
    return t;
  }
  private JsDocToken eatTokensUntilEOL() {
    return eatTokensUntilEOL(next());
  }
  private JsDocToken eatTokensUntilEOL(JsDocToken token) {
    do {
      if(token == JsDocToken.EOL || token == JsDocToken.EOC || token == JsDocToken.EOF) {
        state = State.SEARCHING_ANNOTATION;
        return token;
      }
      token = next();
    }while(true);
  }
  private JsDocToken next() {
    if(unreadToken == NO_UNREAD_TOKEN) {
      return stream.getJsDocToken();
    }
    else {
      return current();
    }
  }
  private JsDocToken parseModifiesTag(JsDocToken token) {
    if(token == JsDocToken.LC) {
      Set<String> modifies = new HashSet<String>();
      while(true){
        if(match(JsDocToken.STRING)) {
          String name = stream.getString();
          if(!modifiesAnnotationKeywords.contains(name) && !jsdocBuilder.hasParameter(name)) {
            parser.addParserWarning("msg.jsdoc.modifies.unknown", name, stream.getLineno(), stream.getCharno());
          }
          modifies.add(stream.getString());
          token = next();
        }
        else {
          parser.addParserWarning("msg.jsdoc.modifies", stream.getLineno(), stream.getCharno());
          return token;
        }
        if(match(JsDocToken.PIPE)) {
          token = next();
        }
        else {
          break ;
        }
      }
      if(!match(JsDocToken.RC)) {
        parser.addParserWarning("msg.jsdoc.modifies", stream.getLineno(), stream.getCharno());
      }
      else {
        token = next();
        if(!jsdocBuilder.recordModifies(modifies)) {
          parser.addParserWarning("msg.jsdoc.modifies.duplicate", stream.getLineno(), stream.getCharno());
        }
      }
    }
    return token;
  }
  private JsDocToken parseSuppressTag(JsDocToken token) {
    if(token == JsDocToken.LC) {
      Set<String> suppressions = new HashSet<String>();
      while(true){
        if(match(JsDocToken.STRING)) {
          String name = stream.getString();
          if(!suppressionNames.contains(name)) {
            parser.addParserWarning("msg.jsdoc.suppress.unknown", name, stream.getLineno(), stream.getCharno());
          }
          suppressions.add(stream.getString());
          token = next();
        }
        else {
          parser.addParserWarning("msg.jsdoc.suppress", stream.getLineno(), stream.getCharno());
          return token;
        }
        if(match(JsDocToken.PIPE)) {
          token = next();
        }
        else {
          break ;
        }
      }
      if(!match(JsDocToken.RC)) {
        parser.addParserWarning("msg.jsdoc.suppress", stream.getLineno(), stream.getCharno());
      }
      else {
        token = next();
        if(!jsdocBuilder.recordSuppressions(suppressions)) {
          parser.addParserWarning("msg.jsdoc.suppress.duplicate", stream.getLineno(), stream.getCharno());
        }
      }
    }
    return token;
  }
  private Node createTemplateNode() {
    Node templateNode = IR.script();
    templateNode.setStaticSourceFile(this.associatedNode != null ? this.associatedNode.getStaticSourceFile() : null);
    return templateNode;
  }
  private Node newNode(int type) {
    return new Node(type, stream.getLineno(), stream.getCharno()).clonePropsFrom(templateNode);
  }
  private Node newStringNode(String s) {
    return newStringNode(s, stream.getLineno(), stream.getCharno());
  }
  private Node newStringNode(String s, int lineno, int charno) {
    Node n = Node.newString(s, lineno, charno).clonePropsFrom(templateNode);
    n.setLength(s.length());
    return n;
  }
  private Node parseAndRecordParamTypeNode(JsDocToken token) {
    Preconditions.checkArgument(token == JsDocToken.LC);
    int lineno = stream.getLineno();
    int startCharno = stream.getCharno();
    Node typeNode = parseParamTypeExpressionAnnotation(token);
    if(typeNode != null) {
      int endLineno = stream.getLineno();
      int endCharno = stream.getCharno();
      jsdocBuilder.markTypeNode(typeNode, lineno, startCharno, endLineno, endCharno, true);
    }
    return typeNode;
  }
  private Node parseAndRecordTypeNameNode(JsDocToken token, int lineno, int startCharno, boolean matchingLC) {
    return parseAndRecordTypeNode(token, lineno, startCharno, matchingLC, true);
  }
  private Node parseAndRecordTypeNode(JsDocToken token) {
    return parseAndRecordTypeNode(token, token == JsDocToken.LC);
  }
  private Node parseAndRecordTypeNode(JsDocToken token, boolean matchingLC) {
    return parseAndRecordTypeNode(token, stream.getLineno(), stream.getCharno(), matchingLC, false);
  }
  private Node parseAndRecordTypeNode(JsDocToken token, int lineno, int startCharno, boolean matchingLC, boolean onlyParseSimpleNames) {
    Node typeNode = null;
    if(onlyParseSimpleNames) {
      typeNode = parseTypeNameAnnotation(token);
    }
    else {
      typeNode = parseTypeExpressionAnnotation(token);
    }
    if(typeNode != null) {
      int endLineno = stream.getLineno();
      int endCharno = stream.getCharno();
      jsdocBuilder.markTypeNode(typeNode, lineno, startCharno, endLineno, endCharno, matchingLC);
    }
    return typeNode;
  }
  private Node parseArrayType(JsDocToken token) {
    Node array = newNode(Token.LB);
    Node arg = null;
    boolean hasVarArgs = false;
    do {
      if(arg != null) {
        next();
        skipEOLs();
        token = next();
      }
      if(token == JsDocToken.ELLIPSIS) {
        arg = wrapNode(Token.ELLIPSIS, parseTypeExpression(next()));
        hasVarArgs = true;
      }
      else {
        arg = parseTypeExpression(token);
      }
      if(arg == null) {
        return null;
      }
      array.addChildToBack(arg);
      if(hasVarArgs) {
        break ;
      }
      skipEOLs();
    }while(match(JsDocToken.COMMA));
    if(!match(JsDocToken.RB)) {
      return reportTypeSyntaxWarning("msg.jsdoc.missing.rb");
    }
    next();
    return array;
  }
  private Node parseBasicTypeExpression(JsDocToken token) {
    if(token == JsDocToken.STAR) {
      return newNode(Token.STAR);
    }
    else 
      if(token == JsDocToken.LB) {
        skipEOLs();
        return parseArrayType(next());
      }
      else 
        if(token == JsDocToken.LC) {
          skipEOLs();
          return parseRecordType(next());
        }
        else 
          if(token == JsDocToken.LP) {
            skipEOLs();
            return parseUnionType(next());
          }
          else 
            if(token == JsDocToken.STRING) {
              String string = stream.getString();
              if("function".equals(string)) {
                skipEOLs();
                return parseFunctionType(next());
              }
              else 
                if("null".equals(string) || "undefined".equals(string)) {
                  return newStringNode(string);
                }
                else {
                  return parseTypeName(token);
                }
            }
    restoreLookAhead(token);
    return reportGenericTypeSyntaxWarning();
  }
  private Node parseFieldName(JsDocToken token) {
    switch (token){
      case STRING:
      String string = stream.getString();
      return newStringNode(string);
      default:
      return null;
    }
  }
  private Node parseFieldType(JsDocToken token) {
    Node fieldName = parseFieldName(token);
    if(fieldName == null) {
      return null;
    }
    skipEOLs();
    if(!match(JsDocToken.COLON)) {
      return fieldName;
    }
    next();
    skipEOLs();
    Node typeExpression = parseTypeExpression(next());
    if(typeExpression == null) {
      return null;
    }
    Node fieldType = newNode(Token.COLON);
    fieldType.addChildToBack(fieldName);
    fieldType.addChildToBack(typeExpression);
    return fieldType;
  }
  private Node parseFieldTypeList(JsDocToken token) {
    Node fieldTypeList = newNode(Token.LB);
    do {
      Node fieldType = parseFieldType(token);
      if(fieldType == null) {
        return null;
      }
      fieldTypeList.addChildToBack(fieldType);
      skipEOLs();
      if(!match(JsDocToken.COMMA)) {
        break ;
      }
      next();
      skipEOLs();
      token = next();
    }while(true);
    return fieldTypeList;
  }
  private Node parseFunctionType(JsDocToken token) {
    if(token != JsDocToken.LP) {
      restoreLookAhead(token);
      return reportTypeSyntaxWarning("msg.jsdoc.missing.lp");
    }
    Node functionType = newNode(Token.FUNCTION);
    Node parameters = null;
    skipEOLs();
    if(!match(JsDocToken.RP)) {
      token = next();
      boolean hasParams = true;
      if(token == JsDocToken.STRING) {
        String tokenStr = stream.getString();
        boolean isThis = "this".equals(tokenStr);
        boolean isNew = "new".equals(tokenStr);
        if(isThis || isNew) {
          if(match(JsDocToken.COLON)) {
            next();
            skipEOLs();
            Node contextType = wrapNode(isThis ? Token.THIS : Token.NEW, parseTypeName(next()));
            if(contextType == null) {
              return null;
            }
            functionType.addChildToFront(contextType);
          }
          else {
            return reportTypeSyntaxWarning("msg.jsdoc.missing.colon");
          }
          if(match(JsDocToken.COMMA)) {
            next();
            skipEOLs();
            token = next();
          }
          else {
            hasParams = false;
          }
        }
      }
      if(hasParams) {
        parameters = parseParametersType(token);
        if(parameters == null) {
          return null;
        }
      }
    }
    if(parameters != null) {
      functionType.addChildToBack(parameters);
    }
    skipEOLs();
    if(!match(JsDocToken.RP)) {
      return reportTypeSyntaxWarning("msg.jsdoc.missing.rp");
    }
    skipEOLs();
    Node resultType = parseResultType(next());
    if(resultType == null) {
      return null;
    }
    else {
      functionType.addChildToBack(resultType);
    }
    return functionType;
  }
  private Node parseParamTypeExpressionAnnotation(JsDocToken token) {
    Preconditions.checkArgument(token == JsDocToken.LC);
    skipEOLs();
    boolean restArg = false;
    token = next();
    if(token == JsDocToken.ELLIPSIS) {
      token = next();
      if(token == JsDocToken.RC) {
        return wrapNode(Token.ELLIPSIS, IR.empty());
      }
      restArg = true;
    }
    Node typeNode = parseTopLevelTypeExpression(token);
    if(typeNode != null) {
      skipEOLs();
      if(restArg) {
        typeNode = wrapNode(Token.ELLIPSIS, typeNode);
      }
      else 
        if(match(JsDocToken.EQUALS)) {
          next();
          skipEOLs();
          typeNode = wrapNode(Token.EQUALS, typeNode);
        }
      if(!match(JsDocToken.RC)) {
        reportTypeSyntaxWarning("msg.jsdoc.missing.rc");
      }
      else {
        next();
      }
    }
    return typeNode;
  }
  private Node parseParametersType(JsDocToken token) {
    Node paramsType = newNode(Token.PARAM_LIST);
    boolean isVarArgs = false;
    Node paramType = null;
    if(token != JsDocToken.RP) {
      do {
        if(paramType != null) {
          next();
          skipEOLs();
          token = next();
        }
        if(token == JsDocToken.ELLIPSIS) {
          skipEOLs();
          if(match(JsDocToken.RP)) {
            paramType = newNode(Token.ELLIPSIS);
          }
          else {
            skipEOLs();
            if(!match(JsDocToken.LB)) {
              return reportTypeSyntaxWarning("msg.jsdoc.missing.lb");
            }
            next();
            skipEOLs();
            paramType = wrapNode(Token.ELLIPSIS, parseTypeExpression(next()));
            skipEOLs();
            if(!match(JsDocToken.RB)) {
              return reportTypeSyntaxWarning("msg.jsdoc.missing.rb");
            }
            skipEOLs();
            next();
          }
          isVarArgs = true;
        }
        else {
          paramType = parseTypeExpression(token);
          if(match(JsDocToken.EQUALS)) {
            skipEOLs();
            next();
            paramType = wrapNode(Token.EQUALS, paramType);
          }
        }
        if(paramType == null) {
          return null;
        }
        paramsType.addChildToBack(paramType);
        if(isVarArgs) {
          break ;
        }
      }while(match(JsDocToken.COMMA));
    }
    if(isVarArgs && match(JsDocToken.COMMA)) {
      return reportTypeSyntaxWarning("msg.jsdoc.function.varargs");
    }
    return paramsType;
  }
  private Node parseRecordType(JsDocToken token) {
    Node recordType = newNode(Token.LC);
    Node fieldTypeList = parseFieldTypeList(token);
    if(fieldTypeList == null) {
      return reportGenericTypeSyntaxWarning();
    }
    skipEOLs();
    if(!match(JsDocToken.RC)) {
      return reportTypeSyntaxWarning("msg.jsdoc.missing.rc");
    }
    next();
    recordType.addChildToBack(fieldTypeList);
    return recordType;
  }
  private Node parseResultType(JsDocToken token) {
    skipEOLs();
    if(!match(JsDocToken.COLON)) {
      return newNode(Token.EMPTY);
    }
    token = next();
    skipEOLs();
    if(match(JsDocToken.STRING) && "void".equals(stream.getString())) {
      next();
      return newNode(Token.VOID);
    }
    else {
      return parseTypeExpression(next());
    }
  }
  private Node parseTopLevelTypeExpression(JsDocToken token) {
    Node typeExpr = parseTypeExpression(token);
    if(typeExpr != null) {
      if(match(JsDocToken.PIPE)) {
        next();
        if(match(JsDocToken.PIPE)) {
          next();
        }
        skipEOLs();
        token = next();
        return parseUnionTypeWithAlternate(token, typeExpr);
      }
    }
    return typeExpr;
  }
  private Node parseTypeExpression(JsDocToken token) {
    if(token == JsDocToken.QMARK) {
      token = next();
      if(token == JsDocToken.COMMA || token == JsDocToken.EQUALS || token == JsDocToken.RB || token == JsDocToken.RC || token == JsDocToken.RP || token == JsDocToken.PIPE || token == JsDocToken.GT) {
        restoreLookAhead(token);
        return newNode(Token.QMARK);
      }
      return wrapNode(Token.QMARK, parseBasicTypeExpression(token));
    }
    else 
      if(token == JsDocToken.BANG) {
        return wrapNode(Token.BANG, parseBasicTypeExpression(next()));
      }
      else {
        Node basicTypeExpr = parseBasicTypeExpression(token);
        if(basicTypeExpr != null) {
          if(match(JsDocToken.QMARK)) {
            next();
            return wrapNode(Token.QMARK, basicTypeExpr);
          }
          else 
            if(match(JsDocToken.BANG)) {
              next();
              return wrapNode(Token.BANG, basicTypeExpr);
            }
        }
        return basicTypeExpr;
      }
  }
  private Node parseTypeExpressionAnnotation(JsDocToken token) {
    if(token == JsDocToken.LC) {
      skipEOLs();
      Node typeNode = parseTopLevelTypeExpression(next());
      if(typeNode != null) {
        skipEOLs();
        if(!match(JsDocToken.RC)) {
          reportTypeSyntaxWarning("msg.jsdoc.missing.rc");
        }
        else {
          next();
        }
      }
      return typeNode;
    }
    else {
      return parseTypeExpression(token);
    }
  }
  private Node parseTypeExpressionList(JsDocToken token) {
    Node typeExpr = parseTopLevelTypeExpression(token);
    if(typeExpr == null) {
      return null;
    }
    Node typeList = IR.block();
    typeList.addChildToBack(typeExpr);
    while(match(JsDocToken.COMMA)){
      next();
      skipEOLs();
      typeExpr = parseTopLevelTypeExpression(next());
      if(typeExpr == null) {
        return null;
      }
      typeList.addChildToBack(typeExpr);
    }
    return typeList;
  }
  private Node parseTypeName(JsDocToken token) {
    if(token != JsDocToken.STRING) {
      return reportGenericTypeSyntaxWarning();
    }
    String typeName = stream.getString();
    int lineno = stream.getLineno();
    int charno = stream.getCharno();
    while(match(JsDocToken.EOL) && typeName.charAt(typeName.length() - 1) == '.'){
      skipEOLs();
      if(match(JsDocToken.STRING)) {
        next();
        typeName += stream.getString();
      }
    }
    Node typeNameNode = newStringNode(typeName, lineno, charno);
    if(match(JsDocToken.LT)) {
      next();
      skipEOLs();
      Node memberType = parseTypeExpressionList(next());
      if(memberType != null) {
        typeNameNode.addChildToFront(memberType);
        skipEOLs();
        if(!match(JsDocToken.GT)) {
          return reportTypeSyntaxWarning("msg.jsdoc.missing.gt");
        }
        next();
      }
    }
    return typeNameNode;
  }
  private Node parseTypeNameAnnotation(JsDocToken token) {
    if(token == JsDocToken.LC) {
      skipEOLs();
      Node typeNode = parseTypeName(next());
      if(typeNode != null) {
        skipEOLs();
        if(!match(JsDocToken.RC)) {
          reportTypeSyntaxWarning("msg.jsdoc.missing.rc");
        }
        else {
          next();
        }
      }
      return typeNode;
    }
    else {
      return parseTypeName(token);
    }
  }
  public static Node parseTypeString(String typeString) {
    Config config = new Config(Sets.<String>newHashSet(), Sets.<String>newHashSet(), false, LanguageMode.ECMASCRIPT3, false);
    JsDocInfoParser parser = new JsDocInfoParser(new JsDocTokenStream(typeString), null, null, config, NullErrorReporter.forNewRhino());
    return parser.parseTopLevelTypeExpression(parser.next());
  }
  private Node parseUnionType(JsDocToken token) {
    return parseUnionTypeWithAlternate(token, null);
  }
  private Node parseUnionTypeWithAlternate(JsDocToken token, Node alternate) {
    Node union = newNode(Token.PIPE);
    if(alternate != null) {
      union.addChildToBack(alternate);
    }
    Node expr = null;
    do {
      if(expr != null) {
        skipEOLs();
        token = next();
        Preconditions.checkState(token == JsDocToken.PIPE || token == JsDocToken.COMMA);
        boolean isPipe = token == JsDocToken.PIPE;
        if(isPipe && match(JsDocToken.PIPE)) {
          next();
        }
        skipEOLs();
        token = next();
      }
      expr = parseTypeExpression(token);
      if(expr == null) {
        return null;
      }
      union.addChildToBack(expr);
    }while(match(JsDocToken.PIPE, JsDocToken.COMMA));
    if(alternate == null) {
      skipEOLs();
      if(!match(JsDocToken.RP)) {
        return reportTypeSyntaxWarning("msg.jsdoc.missing.rp");
      }
      next();
    }
    return union;
  }
  private Node reportGenericTypeSyntaxWarning() {
    return reportTypeSyntaxWarning("msg.jsdoc.type.syntax");
  }
  private Node reportTypeSyntaxWarning(String warning) {
    parser.addTypeWarning(warning, stream.getLineno(), stream.getCharno());
    return null;
  }
  private Node wrapNode(int type, Node n) {
    return n == null ? null : new Node(type, n, stream.getLineno(), stream.getCharno()).clonePropsFrom(templateNode);
  }
  private String getSourceName() {
    return sourceFile == null ? null : sourceFile.getName();
  }
  private String toString(JsDocToken token) {
    switch (token){
      case ANNOTATION:
      return "@" + stream.getString();
      case BANG:
      return "!";
      case COMMA:
      return ",";
      case COLON:
      return ":";
      case GT:
      return ">";
      case LB:
      return "[";
      case LC:
      return "{";
      case LP:
      return "(";
      case LT:
      return ".<";
      case QMARK:
      return "?";
      case PIPE:
      return "|";
      case RB:
      return "]";
      case RC:
      return "}";
      case RP:
      return ")";
      case STAR:
      return "*";
      case ELLIPSIS:
      return "...";
      case EQUALS:
      return "=";
      case STRING:
      return stream.getString();
      default:
      throw new IllegalStateException(token.toString());
    }
  }
  private static String trimEnd(String s) {
    int trimCount = 0;
    while(trimCount < s.length()){
      char ch = s.charAt(s.length() - trimCount - 1);
      if(Character.isWhitespace(ch)) {
        trimCount++;
      }
      else {
        break ;
      }
    }
    if(trimCount == 0) {
      return s;
    }
    return s.substring(0, s.length() - trimCount);
  }
  private boolean hasParsedFileOverviewDocInfo() {
    return jsdocBuilder.isPopulatedWithFileOverview();
  }
  boolean hasParsedJSDocInfo() {
    return jsdocBuilder.isPopulated();
  }
  private boolean lookAheadForTypeAnnotation() {
    boolean matchedLc = false;
    int c;
    while(true){
      c = stream.getChar();
      if(c == ' ') {
        continue ;
      }
      else 
        if(c == '{') {
          matchedLc = true;
          break ;
        }
        else {
          break ;
        }
    }
    stream.ungetChar(c);
    return matchedLc;
  }
  private boolean match(JsDocToken token) {
    unreadToken = next();
    return unreadToken == token;
  }
  private boolean match(JsDocToken token1, JsDocToken token2) {
    unreadToken = next();
    return unreadToken == token1 || unreadToken == token2;
  }
  @SuppressWarnings(value = {"incomplete-switch", }) boolean parse() {
    int lineno;
    int charno;
    JSTypeExpression type;
    state = State.SEARCHING_ANNOTATION;
    skipEOLs();
    JsDocToken token = next();
    List<ExtendedTypeInfo> extendedTypes = Lists.newArrayList();
    if(jsdocBuilder.shouldParseDocumentation()) {
      ExtractionInfo blockInfo = extractBlockComment(token);
      token = blockInfo.token;
      if(!blockInfo.string.isEmpty()) {
        jsdocBuilder.recordBlockDescription(blockInfo.string);
      }
    }
    else {
      if(token != JsDocToken.ANNOTATION && token != JsDocToken.EOC) {
        jsdocBuilder.recordBlockDescription("");
      }
    }
    retry:
      for(; true; ) {
        switch (token){
          case ANNOTATION:
          if(state == State.SEARCHING_ANNOTATION) {
            state = State.SEARCHING_NEWLINE;
            lineno = stream.getLineno();
            charno = stream.getCharno();
            String annotationName = stream.getString();
            Annotation annotation = annotationNames.get(annotationName);
            if(annotation == null) {
              parser.addParserWarning("msg.bad.jsdoc.tag", annotationName, stream.getLineno(), stream.getCharno());
            }
            else {
              jsdocBuilder.markAnnotation(annotationName, lineno, charno);
              switch (annotation){
                case AUTHOR:
                boolean var_2298 = jsdocBuilder.shouldParseDocumentation();
                if(var_2298) {
                  ExtractionInfo authorInfo = extractSingleLineBlock();
                  String author = authorInfo.string;
                  if(author.length() == 0) {
                    parser.addParserWarning("msg.jsdoc.authormissing", stream.getLineno(), stream.getCharno());
                  }
                  else {
                    jsdocBuilder.addAuthor(author);
                  }
                  token = authorInfo.token;
                }
                else {
                  token = eatTokensUntilEOL(token);
                }
                continue retry;
                case CONSISTENTIDGENERATOR:
                if(!jsdocBuilder.recordConsistentIdGenerator()) {
                  parser.addParserWarning("msg.jsdoc.consistidgen", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case STRUCT:
                if(!jsdocBuilder.recordStruct()) {
                  parser.addTypeWarning("msg.jsdoc.incompat.type", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case DICT:
                if(!jsdocBuilder.recordDict()) {
                  parser.addTypeWarning("msg.jsdoc.incompat.type", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case CONSTRUCTOR:
                if(!jsdocBuilder.recordConstructor()) {
                  if(jsdocBuilder.isInterfaceRecorded()) {
                    parser.addTypeWarning("msg.jsdoc.interface.constructor", stream.getLineno(), stream.getCharno());
                  }
                  else {
                    parser.addTypeWarning("msg.jsdoc.incompat.type", stream.getLineno(), stream.getCharno());
                  }
                }
                token = eatTokensUntilEOL();
                continue retry;
                case DEPRECATED:
                if(!jsdocBuilder.recordDeprecated()) {
                  parser.addParserWarning("msg.jsdoc.deprecated", stream.getLineno(), stream.getCharno());
                }
                ExtractionInfo reasonInfo = extractMultilineTextualBlock(token);
                String reason = reasonInfo.string;
                if(reason.length() > 0) {
                  jsdocBuilder.recordDeprecationReason(reason);
                }
                token = reasonInfo.token;
                continue retry;
                case INTERFACE:
                if(!jsdocBuilder.recordInterface()) {
                  if(jsdocBuilder.isConstructorRecorded()) {
                    parser.addTypeWarning("msg.jsdoc.interface.constructor", stream.getLineno(), stream.getCharno());
                  }
                  else {
                    parser.addTypeWarning("msg.jsdoc.incompat.type", stream.getLineno(), stream.getCharno());
                  }
                }
                token = eatTokensUntilEOL();
                continue retry;
                case DESC:
                if(jsdocBuilder.isDescriptionRecorded()) {
                  parser.addParserWarning("msg.jsdoc.desc.extra", stream.getLineno(), stream.getCharno());
                  token = eatTokensUntilEOL();
                  continue retry;
                }
                else {
                  ExtractionInfo descriptionInfo = extractMultilineTextualBlock(token);
                  String description = descriptionInfo.string;
                  jsdocBuilder.recordDescription(description);
                  token = descriptionInfo.token;
                  continue retry;
                }
                case FILE_OVERVIEW:
                String fileOverview = "";
                if(jsdocBuilder.shouldParseDocumentation()) {
                  ExtractionInfo fileOverviewInfo = extractMultilineTextualBlock(token, WhitespaceOption.TRIM);
                  fileOverview = fileOverviewInfo.string;
                  token = fileOverviewInfo.token;
                }
                else {
                  token = eatTokensUntilEOL(token);
                }
                if(!jsdocBuilder.recordFileOverview(fileOverview)) {
                  parser.addParserWarning("msg.jsdoc.fileoverview.extra", stream.getLineno(), stream.getCharno());
                }
                continue retry;
                case LICENSE:
                case PRESERVE:
                ExtractionInfo preserveInfo = extractMultilineTextualBlock(token, WhitespaceOption.PRESERVE);
                String preserve = preserveInfo.string;
                if(preserve.length() > 0) {
                  if(fileLevelJsDocBuilder != null) {
                    fileLevelJsDocBuilder.append(preserve);
                  }
                }
                token = preserveInfo.token;
                continue retry;
                case ENUM:
                token = next();
                lineno = stream.getLineno();
                charno = stream.getCharno();
                type = null;
                if(token != JsDocToken.EOL && token != JsDocToken.EOC) {
                  type = createJSTypeExpression(parseAndRecordTypeNode(token));
                }
                if(type == null) {
                  type = createJSTypeExpression(newStringNode("number"));
                }
                if(!jsdocBuilder.recordEnumParameterType(type)) {
                  parser.addTypeWarning("msg.jsdoc.incompat.type", lineno, charno);
                }
                token = eatTokensUntilEOL(token);
                continue retry;
                case EXPORT:
                if(!jsdocBuilder.recordExport()) {
                  parser.addParserWarning("msg.jsdoc.export", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case EXPOSE:
                if(!jsdocBuilder.recordExpose()) {
                  parser.addParserWarning("msg.jsdoc.expose", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case EXTERNS:
                if(!jsdocBuilder.recordExterns()) {
                  parser.addParserWarning("msg.jsdoc.externs", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case JAVA_DISPATCH:
                if(!jsdocBuilder.recordJavaDispatch()) {
                  parser.addParserWarning("msg.jsdoc.javadispatch", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case EXTENDS:
                case IMPLEMENTS:
                skipEOLs();
                token = next();
                lineno = stream.getLineno();
                charno = stream.getCharno();
                boolean matchingRc = false;
                if(token == JsDocToken.LC) {
                  token = next();
                  matchingRc = true;
                }
                if(token == JsDocToken.STRING) {
                  Node typeNode = parseAndRecordTypeNameNode(token, lineno, charno, matchingRc);
                  lineno = stream.getLineno();
                  charno = stream.getCharno();
                  typeNode = wrapNode(Token.BANG, typeNode);
                  type = createJSTypeExpression(typeNode);
                  if(annotation == Annotation.EXTENDS) {
                    extendedTypes.add(new ExtendedTypeInfo(type, stream.getLineno(), stream.getCharno()));
                  }
                  else {
                    Preconditions.checkState(annotation == Annotation.IMPLEMENTS);
                    if(!jsdocBuilder.recordImplementedInterface(type)) {
                      parser.addTypeWarning("msg.jsdoc.implements.duplicate", lineno, charno);
                    }
                  }
                  token = next();
                  if(matchingRc) {
                    if(token != JsDocToken.RC) {
                      parser.addTypeWarning("msg.jsdoc.missing.rc", stream.getLineno(), stream.getCharno());
                    }
                  }
                  else 
                    if(token != JsDocToken.EOL && token != JsDocToken.EOF && token != JsDocToken.EOC) {
                      parser.addTypeWarning("msg.end.annotation.expected", stream.getLineno(), stream.getCharno());
                    }
                }
                else {
                  parser.addTypeWarning("msg.no.type.name", lineno, charno);
                }
                token = eatTokensUntilEOL(token);
                continue retry;
                case HIDDEN:
                if(!jsdocBuilder.recordHiddenness()) {
                  parser.addParserWarning("msg.jsdoc.hidden", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case LENDS:
                skipEOLs();
                matchingRc = false;
                if(match(JsDocToken.LC)) {
                  token = next();
                  matchingRc = true;
                }
                if(match(JsDocToken.STRING)) {
                  token = next();
                  if(!jsdocBuilder.recordLends(stream.getString())) {
                    parser.addTypeWarning("msg.jsdoc.lends.incompatible", stream.getLineno(), stream.getCharno());
                  }
                }
                else {
                  parser.addTypeWarning("msg.jsdoc.lends.missing", stream.getLineno(), stream.getCharno());
                }
                if(matchingRc && !match(JsDocToken.RC)) {
                  parser.addTypeWarning("msg.jsdoc.missing.rc", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case MEANING:
                ExtractionInfo meaningInfo = extractMultilineTextualBlock(token);
                String meaning = meaningInfo.string;
                token = meaningInfo.token;
                if(!jsdocBuilder.recordMeaning(meaning)) {
                  parser.addParserWarning("msg.jsdoc.meaning.extra", stream.getLineno(), stream.getCharno());
                }
                continue retry;
                case NO_ALIAS:
                if(!jsdocBuilder.recordNoAlias()) {
                  parser.addParserWarning("msg.jsdoc.noalias", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case NO_COMPILE:
                if(!jsdocBuilder.recordNoCompile()) {
                  parser.addParserWarning("msg.jsdoc.nocompile", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case NO_TYPE_CHECK:
                if(!jsdocBuilder.recordNoTypeCheck()) {
                  parser.addParserWarning("msg.jsdoc.nocheck", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case NOT_IMPLEMENTED:
                token = eatTokensUntilEOL();
                continue retry;
                case INHERIT_DOC:
                case OVERRIDE:
                if(!jsdocBuilder.recordOverride()) {
                  parser.addTypeWarning("msg.jsdoc.override", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case THROWS:
                skipEOLs();
                token = next();
                lineno = stream.getLineno();
                charno = stream.getCharno();
                type = null;
                if(token == JsDocToken.LC) {
                  type = createJSTypeExpression(parseAndRecordTypeNode(token));
                  if(type == null) {
                    token = eatTokensUntilEOL();
                    continue retry;
                  }
                }
                token = current();
                jsdocBuilder.recordThrowType(type);
                if(jsdocBuilder.shouldParseDocumentation()) {
                  ExtractionInfo descriptionInfo = extractMultilineTextualBlock(token);
                  String description = descriptionInfo.string;
                  if(description.length() > 0) {
                    jsdocBuilder.recordThrowDescription(type, description);
                  }
                  token = descriptionInfo.token;
                }
                else {
                  token = eatTokensUntilEOL(token);
                }
                continue retry;
                case PARAM:
                skipEOLs();
                token = next();
                lineno = stream.getLineno();
                charno = stream.getCharno();
                type = null;
                if(token == JsDocToken.LC) {
                  type = createJSTypeExpression(parseAndRecordParamTypeNode(token));
                  if(type == null) {
                    token = eatTokensUntilEOL();
                    continue retry;
                  }
                  skipEOLs();
                  token = next();
                  lineno = stream.getLineno();
                  charno = stream.getCharno();
                }
                String name = null;
                boolean isBracketedParam = JsDocToken.LB == token;
                if(isBracketedParam) {
                  token = next();
                }
                if(JsDocToken.STRING != token) {
                  parser.addTypeWarning("msg.missing.variable.name", lineno, charno);
                }
                else {
                  name = stream.getString();
                  if(isBracketedParam) {
                    token = next();
                    if(JsDocToken.EQUALS == token) {
                      token = next();
                      if(JsDocToken.STRING == token) {
                        token = next();
                      }
                    }
                    if(JsDocToken.RB != token) {
                      reportTypeSyntaxWarning("msg.jsdoc.missing.rb");
                    }
                    else 
                      if(type != null) {
                        type = JSTypeExpression.makeOptionalArg(type);
                      }
                  }
                  if(name.indexOf('.') > -1) {
                    name = null;
                  }
                  else 
                    if(!jsdocBuilder.recordParameter(name, type)) {
                      if(jsdocBuilder.hasParameter(name)) {
                        parser.addTypeWarning("msg.dup.variable.name", name, lineno, charno);
                      }
                      else {
                        parser.addTypeWarning("msg.jsdoc.incompat.type", name, lineno, charno);
                      }
                    }
                }
                if(name == null) {
                  token = eatTokensUntilEOL(token);
                  continue retry;
                }
                jsdocBuilder.markName(name, sourceFile, lineno, charno);
                if(jsdocBuilder.shouldParseDocumentation()) {
                  ExtractionInfo paramDescriptionInfo = extractMultilineTextualBlock(token);
                  String paramDescription = paramDescriptionInfo.string;
                  if(paramDescription.length() > 0) {
                    jsdocBuilder.recordParameterDescription(name, paramDescription);
                  }
                  token = paramDescriptionInfo.token;
                }
                else {
                  token = eatTokensUntilEOL(token);
                }
                continue retry;
                case PRESERVE_TRY:
                if(!jsdocBuilder.recordPreserveTry()) {
                  parser.addParserWarning("msg.jsdoc.preservertry", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case NO_SHADOW:
                if(!jsdocBuilder.recordNoShadow()) {
                  parser.addParserWarning("msg.jsdoc.noshadow", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case NO_SIDE_EFFECTS:
                if(!jsdocBuilder.recordNoSideEffects()) {
                  parser.addParserWarning("msg.jsdoc.nosideeffects", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case MODIFIES:
                token = parseModifiesTag(next());
                continue retry;
                case IMPLICIT_CAST:
                if(!jsdocBuilder.recordImplicitCast()) {
                  parser.addTypeWarning("msg.jsdoc.implicitcast", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case SEE:
                if(jsdocBuilder.shouldParseDocumentation()) {
                  ExtractionInfo referenceInfo = extractSingleLineBlock();
                  String reference = referenceInfo.string;
                  if(reference.length() == 0) {
                    parser.addParserWarning("msg.jsdoc.seemissing", stream.getLineno(), stream.getCharno());
                  }
                  else {
                    jsdocBuilder.addReference(reference);
                  }
                  token = referenceInfo.token;
                }
                else {
                  token = eatTokensUntilEOL(token);
                }
                continue retry;
                case STABLEIDGENERATOR:
                if(!jsdocBuilder.recordStableIdGenerator()) {
                  parser.addParserWarning("msg.jsdoc.stableidgen", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case SUPPRESS:
                token = parseSuppressTag(next());
                continue retry;
                case TEMPLATE:
                ExtractionInfo templateInfo = extractSingleLineBlock();
                List<String> names = Lists.newArrayList(Splitter.on(',').trimResults().split(templateInfo.string));
                if(names.size() == 0 || names.get(0).length() == 0) {
                  parser.addTypeWarning("msg.jsdoc.templatemissing", stream.getLineno(), stream.getCharno());
                }
                else 
                  if(!jsdocBuilder.recordTemplateTypeNames(names)) {
                    parser.addTypeWarning("msg.jsdoc.template.at.most.once", stream.getLineno(), stream.getCharno());
                  }
                token = templateInfo.token;
                continue retry;
                case IDGENERATOR:
                if(!jsdocBuilder.recordIdGenerator()) {
                  parser.addParserWarning("msg.jsdoc.idgen", stream.getLineno(), stream.getCharno());
                }
                token = eatTokensUntilEOL();
                continue retry;
                case VERSION:
                ExtractionInfo versionInfo = extractSingleLineBlock();
                String version = versionInfo.string;
                if(version.length() == 0) {
                  parser.addParserWarning("msg.jsdoc.versionmissing", stream.getLineno(), stream.getCharno());
                }
                else {
                  if(!jsdocBuilder.recordVersion(version)) {
                    parser.addParserWarning("msg.jsdoc.extraversion", stream.getLineno(), stream.getCharno());
                  }
                }
                token = versionInfo.token;
                continue retry;
                case CONSTANT:
                case DEFINE:
                case RETURN:
                case PRIVATE:
                case PROTECTED:
                case PUBLIC:
                case THIS:
                case TYPE:
                case TYPEDEF:
                lineno = stream.getLineno();
                charno = stream.getCharno();
                Node typeNode = null;
                boolean hasType = lookAheadForTypeAnnotation();
                boolean isAlternateTypeAnnotation = (annotation == Annotation.PRIVATE || annotation == Annotation.PROTECTED || annotation == Annotation.PUBLIC || annotation == Annotation.CONSTANT);
                boolean canSkipTypeAnnotation = (isAlternateTypeAnnotation || annotation == Annotation.RETURN);
                type = null;
                if(hasType || !canSkipTypeAnnotation) {
                  skipEOLs();
                  token = next();
                  typeNode = parseAndRecordTypeNode(token);
                  if(annotation == Annotation.THIS) {
                    typeNode = wrapNode(Token.BANG, typeNode);
                  }
                  type = createJSTypeExpression(typeNode);
                }
                boolean hasError = type == null && !canSkipTypeAnnotation;
                if(!hasError) {
                  if((type != null && isAlternateTypeAnnotation) || annotation == Annotation.TYPE) {
                    if(!jsdocBuilder.recordType(type)) {
                      parser.addTypeWarning("msg.jsdoc.incompat.type", lineno, charno);
                    }
                  }
                  switch (annotation){
                    case CONSTANT:
                    if(!jsdocBuilder.recordConstancy()) {
                      parser.addParserWarning("msg.jsdoc.const", stream.getLineno(), stream.getCharno());
                    }
                    break ;
                    case DEFINE:
                    if(!jsdocBuilder.recordDefineType(type)) {
                      parser.addParserWarning("msg.jsdoc.define", lineno, charno);
                    }
                    break ;
                    case PRIVATE:
                    if(!jsdocBuilder.recordVisibility(Visibility.PRIVATE)) {
                      parser.addParserWarning("msg.jsdoc.visibility.private", lineno, charno);
                    }
                    break ;
                    case PROTECTED:
                    if(!jsdocBuilder.recordVisibility(Visibility.PROTECTED)) {
                      parser.addParserWarning("msg.jsdoc.visibility.protected", lineno, charno);
                    }
                    break ;
                    case PUBLIC:
                    if(!jsdocBuilder.recordVisibility(Visibility.PUBLIC)) {
                      parser.addParserWarning("msg.jsdoc.visibility.public", lineno, charno);
                    }
                    break ;
                    case RETURN:
                    if(type == null) {
                      type = createJSTypeExpression(newNode(Token.QMARK));
                    }
                    if(!jsdocBuilder.recordReturnType(type)) {
                      parser.addTypeWarning("msg.jsdoc.incompat.type", lineno, charno);
                      break ;
                    }
                    if(jsdocBuilder.shouldParseDocumentation()) {
                      ExtractionInfo returnDescriptionInfo = extractMultilineTextualBlock(token);
                      String returnDescription = returnDescriptionInfo.string;
                      if(returnDescription.length() > 0) {
                        jsdocBuilder.recordReturnDescription(returnDescription);
                      }
                      token = returnDescriptionInfo.token;
                    }
                    else {
                      token = eatTokensUntilEOL(token);
                    }
                    continue retry;
                    case THIS:
                    if(!jsdocBuilder.recordThisType(type)) {
                      parser.addTypeWarning("msg.jsdoc.incompat.type", lineno, charno);
                    }
                    break ;
                    case TYPEDEF:
                    if(!jsdocBuilder.recordTypedef(type)) {
                      parser.addTypeWarning("msg.jsdoc.incompat.type", lineno, charno);
                    }
                    break ;
                  }
                }
                token = eatTokensUntilEOL();
                continue retry;
              }
            }
          }
          break ;
          case EOC:
          if(hasParsedFileOverviewDocInfo()) {
            fileOverviewJSDocInfo = retrieveAndResetParsedJSDocInfo();
          }
          checkExtendedTypes(extendedTypes);
          return true;
          case EOF:
          jsdocBuilder.build(null);
          parser.addParserWarning("msg.unexpected.eof", stream.getLineno(), stream.getCharno());
          checkExtendedTypes(extendedTypes);
          return false;
          case EOL:
          if(state == State.SEARCHING_NEWLINE) {
            state = State.SEARCHING_ANNOTATION;
          }
          token = next();
          continue retry;
          default:
          if(token == JsDocToken.STAR && state == State.SEARCHING_ANNOTATION) {
            token = next();
            continue retry;
          }
          else {
            state = State.SEARCHING_NEWLINE;
            token = eatTokensUntilEOL();
            continue retry;
          }
        }
        token = next();
      }
  }
  private void checkExtendedTypes(List<ExtendedTypeInfo> extendedTypes) {
    for (ExtendedTypeInfo typeInfo : extendedTypes) {
      if(jsdocBuilder.isInterfaceRecorded()) {
        if(!jsdocBuilder.recordExtendedInterface(typeInfo.type)) {
          parser.addParserWarning("msg.jsdoc.extends.duplicate", typeInfo.lineno, typeInfo.charno);
        }
      }
      else {
        if(!jsdocBuilder.recordBaseType(typeInfo.type)) {
          parser.addTypeWarning("msg.jsdoc.incompat.type", typeInfo.lineno, typeInfo.charno);
        }
      }
    }
  }
  private void restoreLookAhead(JsDocToken token) {
    unreadToken = token;
  }
  void setFileLevelJsDocBuilder(Node.FileLevelJsDocBuilder fileLevelJsDocBuilder) {
    this.fileLevelJsDocBuilder = fileLevelJsDocBuilder;
  }
  void setFileOverviewJSDocInfo(JSDocInfo fileOverviewJSDocInfo) {
    this.fileOverviewJSDocInfo = fileOverviewJSDocInfo;
  }
  private void skipEOLs() {
    while(match(JsDocToken.EOL)){
      next();
      if(match(JsDocToken.STAR)) {
        next();
      }
    }
  }
  
  private class ErrorReporterParser  {
    void addParserWarning(String messageId, int lineno, int charno) {
      errorReporter.warning(ScriptRuntime.getMessage0(messageId), getSourceName(), lineno, null, charno);
    }
    void addParserWarning(String messageId, String messageArg, int lineno, int charno) {
      errorReporter.warning(ScriptRuntime.getMessage1(messageId, messageArg), getSourceName(), lineno, null, charno);
    }
    void addTypeWarning(String messageId, int lineno, int charno) {
      errorReporter.warning("Bad type annotation. " + ScriptRuntime.getMessage0(messageId), getSourceName(), lineno, null, charno);
    }
    void addTypeWarning(String messageId, String messageArg, int lineno, int charno) {
      errorReporter.warning("Bad type annotation. " + ScriptRuntime.getMessage1(messageId, messageArg), getSourceName(), lineno, null, charno);
    }
  }
  
  private static class ExtendedTypeInfo  {
    final JSTypeExpression type;
    final int lineno;
    final int charno;
    public ExtendedTypeInfo(JSTypeExpression type, int lineno, int charno) {
      super();
      this.type = type;
      this.lineno = lineno;
      this.charno = charno;
    }
  }
  
  private static class ExtractionInfo  {
    final private String string;
    final private JsDocToken token;
    public ExtractionInfo(String string, JsDocToken token) {
      super();
      this.string = string;
      this.token = token;
    }
  }
  private enum State {
    SEARCHING_ANNOTATION(),

    SEARCHING_NEWLINE(),

    NEXT_IS_ANNOTATION(),

  ;
  private State() {
  }
  }
  private enum WhitespaceOption {
    PRESERVE(),

    TRIM(),

    SINGLE_LINE(),

  ;
  private WhitespaceOption() {
  }
  }
}