# Table of contents
-  [`edamame.core`](#edamame.core) 
    -  [`get-column-number`](#edamame.core/get-column-number)
    -  [`get-line-number`](#edamame.core/get-line-number)
    -  [`iobj?`](#edamame.core/iobj?) - Returns true if obj can carry metadata.
    -  [`normalize-opts`](#edamame.core/normalize-opts) - Expands <code>opts</code> into normalized opts, e.g.
    -  [`parse-next`](#edamame.core/parse-next) - Parses next form from reader.
    -  [`parse-next+string`](#edamame.core/parse-next+string) - Parses next form from reader.
    -  [`parse-string`](#edamame.core/parse-string) - Parses first EDN value from string.
    -  [`parse-string-all`](#edamame.core/parse-string-all) - Like <code>parse-string</code> but parses all values from string and returns them in a vector.
    -  [`reader`](#edamame.core/reader) - Coerces x into indexing pushback-reader to be used with parse-next.
    -  [`source-reader`](#edamame.core/source-reader) - Coerces x into source-logging-reader to be used with parse-next.

-----
# <a name="edamame.core">edamame.core</a>






## <a name="edamame.core/get-column-number">`get-column-number`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L89-L90)
<a name="edamame.core/get-column-number"></a>
``` clojure

(get-column-number reader)
```


## <a name="edamame.core/get-line-number">`get-line-number`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L86-L87)
<a name="edamame.core/get-line-number"></a>
``` clojure

(get-line-number reader)
```


## <a name="edamame.core/iobj?">`iobj?`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L125-L130)
<a name="edamame.core/iobj?"></a>
``` clojure

(iobj? obj)
```


Returns true if obj can carry metadata.

## <a name="edamame.core/normalize-opts">`normalize-opts`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L92-L96)
<a name="edamame.core/normalize-opts"></a>
``` clojure

(normalize-opts opts)
```


Expands `opts` into normalized opts, e.g. `:all true` is expanded
  into explicit options.

## <a name="edamame.core/parse-next">`parse-next`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L98-L111)
<a name="edamame.core/parse-next"></a>
``` clojure

(parse-next reader)
(parse-next reader normalized-opts)
```


Parses next form from reader. Accepts same opts as [`parse-string`](#edamame.core/parse-string),
  but must be normalized with [`normalize-opts`](#edamame.core/normalize-opts) first.

## <a name="edamame.core/parse-next+string">`parse-next+string`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L113-L123)
<a name="edamame.core/parse-next+string"></a>
``` clojure

(parse-next+string reader)
(parse-next+string reader normalized-opts)
```


Parses next form from reader. Accepts same opts as [`parse-string`](#edamame.core/parse-string),
  but must be normalized with [`normalize-opts`](#edamame.core/normalize-opts) first.
  Returns read value + string read (whitespace-trimmed).

## <a name="edamame.core/parse-string">`parse-string`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L7-L64)
<a name="edamame.core/parse-string"></a>
``` clojure

(parse-string s)
(parse-string s opts)
```


Parses first EDN value from string.

  Supported parsing options:

  `:deref`: parse forms starting with `@`. If `true`, the resulting
  expression will be parsed as `(deref expr)`.

  `:fn`: parse function literals (`#(inc %)`). If `true`, will be parsed as `(fn [%1] (inc %))`.

  `:quote`: parse quoted expression `'foo`. If `true`, will be parsed as `(quote foo)`.

  `:read-eval`: parse read-eval (`=(+ 1 2 3)`). If `true`, the
  resulting expression will be parsed as `(read-eval (+ 1 2 3))`.

  `:regex`: parse regex literals (`#"foo"`). If `true`, defaults to
  `re-pattern`.

  `:syntax-quote`: parse syntax-quote (`(+ 1 2 3)`). Symbols get
  qualified using `:resolve-symbol` which defaults to `identity`:
  `(parse-string "`x" {:syntax-quote {:resolve-symbol #(symbol "user" (str %))}})
  ;;=> (quote user/x)`.

  `:var`: parse var literals (`#'foo`). If `true`, the resulting
  expression will be parsed as `(var foo)`.

  `:all`: when `true`, the above options will be set to `true` unless
  explicitly provided.

  Supported options for processing reader conditionals:

  `:read-cond`: - `:allow` to process reader conditionals, or
                  `:preserve` to keep all branches
  `:features`: - persistent set of feature keywords for reader conditionals (e.g. `#{:clj}`).

  `:auto-resolve`: map of alias to namespace symbols for
  auto-resolving keywords. Use `:current` as the alias for the current
  namespace.

  `:readers`: data readers.

  `:postprocess`: a function that is called with a map containing
  `:obj`, the read value, and `:loc`, the location metadata. This can
  be used to handle objects that cannot carry metadata differently. If
  this option is provided, attaching location metadata is not
  automatically added to the object.

  `:location?`: a predicate that is called with the parsed
  object. Should return a truthy value to determine if location
  information will be added.

  Additional arguments to tools.reader may be passed with
  `:tools.reader/opts`, like `:readers` for passing reader tag functions.
  

## <a name="edamame.core/parse-string-all">`parse-string-all`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L66-L72)
<a name="edamame.core/parse-string-all"></a>
``` clojure

(parse-string-all s)
(parse-string-all s opts)
```


Like [`parse-string`](#edamame.core/parse-string) but parses all values from string and returns them
  in a vector.

## <a name="edamame.core/reader">`reader`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L74-L78)
<a name="edamame.core/reader"></a>
``` clojure

(reader x)
```


Coerces x into indexing pushback-reader to be used with
  parse-next. Accepts string or `java.io.Reader`

## <a name="edamame.core/source-reader">`source-reader`</a> [📃](https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L80-L84)
<a name="edamame.core/source-reader"></a>
``` clojure

(source-reader x)
```


Coerces x into source-logging-reader to be used with
  parse-next. Accepts string or `java.io.Reader`
