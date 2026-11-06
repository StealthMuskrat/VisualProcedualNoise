package dev.visualengine.authoring;

import java.util.*;

/** Bounded expression parser. Only typed AST nodes, never original text, reach code generation. */
public final class Formula {
    public enum Type {
        FLOAT(1,"float"),VEC2(2,"vec2"),VEC3(3,"vec3"),VEC4(4,"vec4");
        public final int width;public final String glsl;
        Type(int width,String glsl){this.width=width;this.glsl=glsl;}
    }
    public sealed interface Node permits NumberNode,Symbol,Unary,Binary,Call,Swizzle {
        Type type();String code();int cost();int depth();
    }
    public record NumberNode(double value) implements Node {
        public Type type(){return Type.FLOAT;}public String code(){return Double.toString(value);}public int cost(){return 1;}public int depth(){return 1;}
    }
    public record Symbol(String name,Type type) implements Node {
        public String code(){return switch(name){case "t"->"time";case "phase"->"motionPhase.x";default->name;};}
        public int cost(){return 1;}public int depth(){return 1;}
    }
    public record Unary(char op,Node child) implements Node {
        public Type type(){return child.type();}public String code(){return "("+op+child.code()+")";}
        public int cost(){return child.cost()+1;}public int depth(){return child.depth()+1;}
    }
    public record Binary(char op,Node left,Node right,Type type) implements Node {
        public String code(){return "("+left.code()+op+right.code()+")";}
        public int cost(){return left.cost()+right.cost()+1;}public int depth(){return Math.max(left.depth(),right.depth())+1;}
    }
    public record Call(String name,List<Node> args,Type type) implements Node {
        public String code(){
            boolean broadcast=type!=Type.FLOAT&&Set.of("min","max","safeDiv","powSafe","mix","clamp","smoothstep").contains(name);
            return name+"("+String.join(",",args.stream().map(n->broadcast&&n.type()==Type.FLOAT?type.glsl+"("+n.code()+")":n.code()).toList())+")";
        }
        public int cost(){return args.stream().mapToInt(Node::cost).sum()+switch(name){case "fbm"->320;case "noise3d"->64;case "cellular"->180;default->2;};}
        public int depth(){return args.stream().mapToInt(Node::depth).max().orElse(0)+1;}
    }
    public record Swizzle(Node child,String components,Type type) implements Node {
        public String code(){return "("+child.code()+")."+components;}
        public int cost(){return child.cost()+1;}public int depth(){return child.depth()+1;}
    }
    public record Compiled(Node root,String expression,String gradient,boolean analyticGradient,Set<String> dependencies) {}
    public static final Map<String,Type> SYMBOLS=Map.of("p",Type.VEC2,"t",Type.FLOAT,"phase",Type.FLOAT,
        "frequency",Type.FLOAT,"drift",Type.FLOAT,"stripeRate",Type.FLOAT,"pi",Type.FLOAT);
    private final String input;
    private final Map<String,Type> symbols;
    private int offset,nodes,recursion;
    private Formula(String input,Map<String,Type> symbols){this.input=input;this.symbols=symbols;}
    public static Compiled compile(String source){return compile(source,SYMBOLS);}
    public static Compiled compile(String source,Map<String,Type> symbols) {
        if(source==null||source.length()>8192)throw new IllegalArgumentException("Formula exceeds 8192 characters");
        Formula p=new Formula(source,symbols);Node root=p.expression();p.space();
        if(p.offset!=source.length())throw p.error("Unexpected token");
        if(root.cost()>8192||root.depth()>32||root.code().length()>65536)throw p.error("Expanded formula cost or depth exceeds limit");
        Set<String> dependencies=new TreeSet<>();collect(root,dependencies);
        String derivative=gradient(root);
        return new Compiled(root,root.code(),derivative,derivative!=null,Set.copyOf(dependencies));
    }
    private Node add(Node n){if(++nodes>256)throw error("Formula exceeds 256 syntax nodes");return n;}
    private Node expression() {
        if(++recursion>32)throw error("Formula nesting exceeds 32");
        Node n=product();while(true){if(take('+'))n=binary('+',n,product());else if(take('-'))n=binary('-',n,product());else break;}
        recursion--;return n;
    }
    private Node product(){Node n=unary();while(true){if(take('*'))n=binary('*',n,unary());else if(take('/'))n=binary('/',n,unary());else return n;}}
    private Node unary(){if(take('+'))return add(new Unary('+',unaryBounded()));if(take('-'))return add(new Unary('-',unaryBounded()));return postfix();}
    private Node unaryBounded(){if(++recursion>32)throw error("Formula nesting exceeds 32");Node n=unary();recursion--;return n;}
    private Node binary(char op,Node a,Node b) {
        Type type=compatible(a.type(),b.type());
        if(a instanceof NumberNode x && b instanceof NumberNode y) {
            double v=switch(op){case '+'->x.value()+y.value();case '-'->x.value()-y.value();case '*'->x.value()*y.value();default->x.value()/y.value();};
            if(Double.isFinite(v))return add(new NumberNode(v));
        }
        return add(new Binary(op,a,b,type));
    }
    private Type compatible(Type a,Type b){if(a==b)return a;if(a==Type.FLOAT)return b;if(b==Type.FLOAT)return a;throw error("Scalar/vector size mismatch: "+a+" and "+b);}
    private Node postfix() {
        Node n=primary();
        while(take('.')) {
            String sw=identifier();if(sw.length()<1||sw.length()>4)throw error("Invalid swizzle");
            for(char c:sw.toCharArray())if("xyzw".indexOf(c)<0||"xyzw".indexOf(c)>=n.type().width)throw error("Swizzle exceeds vector width");
            n=add(new Swizzle(n,sw,Type.values()[sw.length()-1]));
        }
        return n;
    }
    private Node primary() {
        space();if(take('(')){Node n=expression();expect(')');return n;}
        if(offset<input.length()&&(Character.isDigit(input.charAt(offset))||input.charAt(offset)=='.')) {
            int start=offset;boolean exponent=false;
            while(offset<input.length()) {
                char c=input.charAt(offset);
                if(Character.isDigit(c)||c=='.'){offset++;continue;}
                if((c=='e'||c=='E')&&!exponent){exponent=true;offset++;if(offset<input.length()&&(input.charAt(offset)=='+'||input.charAt(offset)=='-'))offset++;continue;}
                break;
            }
            try{double v=Double.parseDouble(input.substring(start,offset));if(!Double.isFinite(v)||Math.abs(v)>1e12)throw error("Numeric literal is outside range");return add(new NumberNode(v));}
            catch(NumberFormatException e){throw error("Invalid number");}
        }
        String name=identifier();
        if(take('(')) {
            List<Node> args=new ArrayList<>();if(!take(')')){do{args.add(expression());if(args.size()>4)throw error("Too many function arguments");}while(take(','));expect(')');}
            return call(name,args);
        }
        if(name.equals("pi"))return add(new NumberNode(Math.PI));
        Type type=symbols.get(name);if(type==null)throw error("Unknown symbol: "+name);
        return add(new Symbol(name,type));
    }
    private Node call(String name,List<Node> args) {
        Type type;
        switch(name) {
            case "vec2","vec3","vec4" -> {
                int width=name.charAt(3)-'0',total=args.stream().mapToInt(n->n.type().width).sum();
                if(!(total==width||args.size()==1&&args.getFirst().type()==Type.FLOAT))throw error("Invalid vector constructor");
                type=Type.values()[width-1];
            }
            case "sin","cos","abs","floor","fract","exp","sqrtSafe","normalizeSafe" -> {arity(args,1);type=args.getFirst().type();}
            case "length" -> {arity(args,1);type=Type.FLOAT;}
            case "noise3d","fbm" -> {arity(args,1);require(args.getFirst(),Type.VEC3);type=Type.FLOAT;}
            case "cellular" -> {arity(args,1);require(args.getFirst(),Type.VEC2);type=Type.VEC3;}
            case "dot" -> {arity(args,2);if(args.get(0).type()!=args.get(1).type())throw error("dot requires equal widths");type=Type.FLOAT;}
            case "min","max","safeDiv","powSafe" -> {arity(args,2);type=compatible(args.get(0).type(),args.get(1).type());}
            case "mix","clamp","smoothstep" -> {arity(args,3);type=compatible(compatible(args.get(0).type(),args.get(1).type()),args.get(2).type());}
            default -> throw error("Unknown function: "+name);
        }
        return add(new Call(name,List.copyOf(args),type));
    }
    private void arity(List<Node> args,int count){if(args.size()!=count)throw error("Expected "+count+" arguments");}
    private void require(Node n,Type type){if(n.type()!=type)throw error("Expected "+type+", found "+n.type());}
    private void space(){while(offset<input.length()&&Character.isWhitespace(input.charAt(offset)))offset++;}
    private boolean take(char c){space();if(offset<input.length()&&input.charAt(offset)==c){offset++;return true;}return false;}
    private void expect(char c){if(!take(c))throw error("Expected '"+c+"'");}
    private String identifier(){space();int start=offset;while(offset<input.length()&&(Character.isLetterOrDigit(input.charAt(offset))||input.charAt(offset)=='_'))offset++;if(start==offset||!Character.isLetter(input.charAt(start)))throw error("Expected a name");return input.substring(start,offset);}
    private IllegalArgumentException error(String text){return new IllegalArgumentException(text+" at character "+(offset+1));}
    private static void collect(Node n,Set<String> out) {
        switch(n){case Symbol s->out.add(s.name());case Unary u->collect(u.child(),out);case Binary b->{collect(b.left(),out);collect(b.right(),out);}case Call c->c.args().forEach(a->collect(a,out));case Swizzle s->collect(s.child(),out);default->{}}
    }
    /** Conservative scalar AD subset. Unsupported or nonsmooth trees use differences of the complete field. */
    private static String gradient(Node n) {
        if(n.type()!=Type.FLOAT)return null;
        return switch(n) {
            case NumberNode ignored -> "vec2(0.)";
            case Symbol ignored -> "vec2(0.)";
            case Swizzle s -> s.child() instanceof Symbol v&&v.name().equals("p") ? (s.components().equals("x")?"vec2(1.,0.)":"vec2(0.,1.)") : null;
            case Unary u -> {String d=gradient(u.child());yield d==null?null:"("+u.op()+d+")";}
            case Binary b -> {
                String a=gradient(b.left()),d=gradient(b.right());if(a==null||d==null)yield null;
                yield switch(b.op()){case '+','-'->"("+a+b.op()+d+")";case '*'->"("+a+"*"+b.right().code()+"+"+d+"*"+b.left().code()+")";case '/'->"(("+a+"*"+b.right().code()+"-"+d+"*"+b.left().code()+")/("+b.right().code()+"*"+b.right().code()+"))";default->null;};
            }
            case Call c -> {
                if(c.args().size()!=1)yield null;String d=gradient(c.args().getFirst());if(d==null)yield null;
                yield switch(c.name()){case "sin"->"(cos("+c.args().getFirst().code()+")*"+d+")";case "cos"->"(-sin("+c.args().getFirst().code()+")*"+d+")";default->null;};
            }
        };
    }
}
