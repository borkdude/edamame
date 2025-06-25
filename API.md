# Table of contents
-  [`edamame.core`](#edamame.core) 
    -  [`continue`](#edamame.core/continue) - Singleton value to be used as return value in <code>:read-cond</code> fn to indicate to continue parsing the next form.
    -  [`get-column-number`](#edamame.core/get-column-number)
    -  [`get-line-number`](#edamame.core/get-line-number)
    -  [`iobj?`](#edamame.core/iobj?) - Returns true if obj can carry metadata.
    -  [`normalize-opts`](#edamame.core/normalize-opts) - Expands <code>opts</code> into normalized opts, e.g.
    -  [`parse-next`](#edamame.core/parse-next) - Parses next form from reader.
    -  [`parse-next+string`](#edamame.core/parse-next+string) - Parses next form from reader.
    -  [`parse-ns-form`](#edamame.core/parse-ns-form) - Parses <code>ns-form</code>, an s-expression, into map with: - <code>:name</code>: the name of the namespace - <code>:aliases</code>: a map of aliases to lib names.
    -  [`parse-string`](#edamame.core/parse-string) - Parses first EDN value from string.
    -  [`parse-string-all`](#edamame.core/parse-string-all) - Like <code>parse-string</code> but parses all values from string and returns them in a vector.
    -  [`reader`](#edamame.core/reader) - Coerces x into indexing pushback-reader to be used with parse-next.
    -  [`source-reader`](#edamame.core/source-reader) - Coerces x into source-logging-reader to be used with parse-next.

-----
# <a name="edamame.core">edamame.core</a>






## <a name="edamame.core/continue">`continue`</a>




Singleton value to be used as return value in `:read-cond` fn to indicate to continue parsing the next form
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L158-L160">Source</a></sub></p>

## <a name="edamame.core/get-column-number">`get-column-number`</a>
``` clojure

(get-column-number reader)
```
Function.
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L108-L109">Source</a></sub></p>

## <a name="edamame.core/get-line-number">`get-line-number`</a>
``` clojure

(get-line-number reader)
```
Function.
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L105-L106">Source</a></sub></p>

## <a name="edamame.core/iobj?">`iobj?`</a>
``` clojure

(iobj? obj)
```
Function.

Returns true if obj can carry metadata.
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L144-L149">Source</a></sub></p>

## <a name="edamame.core/normalize-opts">`normalize-opts`</a>
``` clojure

(normalize-opts opts)
```
Function.

Expands `opts` into normalized opts, e.g. `:all true` is expanded
  into explicit options.
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L111-L115">Source</a></sub></p>

## <a name="edamame.core/parse-next">`parse-next`</a>
``` clojure

(parse-next reader)
(parse-next reader normalized-opts)
```
Function.

Parses next form from reader. Accepts same opts as [`parse-string`](#edamame.core/parse-string),
  but must be normalized with [`normalize-opts`](#edamame.core/normalize-opts) first.
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L117-L130">Source</a></sub></p>

## <a name="edamame.core/parse-next+string">`parse-next+string`</a>
``` clojure

(parse-next+string reader)
(parse-next+string reader normalized-opts)
```
Function.

Parses next form from reader. Accepts same opts as [`parse-string`](#edamame.core/parse-string),
  but must be normalized with [`normalize-opts`](#edamame.core/normalize-opts) first.
  Returns read value + string read (whitespace-trimmed).
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L132-L142">Source</a></sub></p>

## <a name="edamame.core/parse-ns-form">`parse-ns-form`</a>
``` clojure

(parse-ns-form ns-form)
```
Function.

Parses `ns-form`, an s-expression, into map with:
  - `:name`: the name of the namespace
  - `:aliases`: a map of aliases to lib names
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L151-L156">Source</a></sub></p>

## <a name="edamame.core/parse-string">`parse-string`</a>
``` clojure

(parse-string s)
(parse-string s opts)
```
Function.

Parses first EDN value from string.

  Supported parsing options can be `true` for default behavior or a function
  that is called on the form and returns a form in its place:

  `:deref`: parse forms starting with `@`. If `true`, the resulting
  expression will be parsed as `(deref expr)`.

  `:fn`: parse function literals (`#(inc %)`). If `true`, will be parsed as `(fn [%1] (inc %))`.

  `:quote`: parse quoted expression `'foo`. If `true`, will be parsed as `(quote foo)`.

  `:read-eval`: parse read-eval (`#=(+ 1 2 3)`). If `true`, the
  resulting expression will be parsed as `(read-eval (+ 1 2 3))`.

  `:regex`: parse regex literals (`#"foo"`). If `true`, defaults to
  `re-pattern`.

  `:var`: parse var literals (`#'foo`). If `true`, the resulting
  expression will be parsed as `(var foo)`.

  `:map`: parse map literal using a custom function, e.g. `flatland.ordered.map/ordered-map`

  `:set`: parse set literal using a custom function, e.g. `flatland.ordered.set/ordered-set`

  `:syntax-quote`: parse syntax-quote (`(+ 1 2 3)`). Symbols get
  qualified using `:resolve-symbol` which defaults to `identity`:
  ```clojure
  (parse-string "`x" {:syntax-quote {:resolve-symbol #(symbol "user" (str %))}})
  ;;=> (quote user/x)
  ```
  By default, also parses `unquote` and `unquote-splicing` literals,
  resolving them accordingly.

  `:unquote`: parse unquote (`~x`). Requires `:syntax-quote` to be set.
  If `true` and not inside `syntax-quote`, defaults to `clojure.core/unquote`.

  `:unquote-splicing`: parse unquote-splicing (`~@x`). Requires `:syntax-quote`
  to be set. If `true` and not inside `syntax-quote`, defaults
  to `clojure.core/unquote-splicing`.

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

  `:uneval`: a function of a map with `:uneval` and `:next` to preserve `#_` expressions by combining them with next value.

  Additional arguments to tools.reader may be passed with
  `:tools.reader/opts`, like `:readers` for passing reader tag functions.
  
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L8-L83">Source</a></sub></p>

## <a name="edamame.core/parse-string-all">`parse-string-all`</a>
``` clojure

(parse-string-all s)
(parse-string-all s opts)
```
Function.

Like [`parse-string`](#edamame.core/parse-string) but parses all values from string and returns them
  in a vector.
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L85-L91">Source</a></sub></p>

## <a name="edamame.core/reader">`reader`</a>
``` clojure

(reader x)
```
Function.

Coerces x into indexing pushback-reader to be used with
  parse-next. Accepts string or `java.io.Reader`
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L93-L97">Source</a></sub></p>

## <a name="edamame.core/source-reader">`source-reader`</a>
``` clojure

(source-reader x)
```
Function.

Coerces x into source-logging-reader to be used with
  parse-next. Accepts string or `java.io.Reader`
<p><sub><a href="https://github.com/borkdude/edamame/blob/master/src/edamame/core.cljc#L99-L103">Source</a></sub></p>
