# restarts

A Clojure library providing basic Common Lisp style restarts for Clojure.

## Usage

```clojure
(with-restart-handlers {Exception (fn [e] (invoke-restart :continue))
                        :foobar (fn [val] (println "Got Value: " val))}
  (with-restart-handlers {RuntimeException (fn [e] (throw-restart (Exception. e)))}
    (let [x (throw-restart (RuntimeException. "Failure")
                           (continue [] {:success true}))]
      (signal :foobar "Cool Value")
      x)))

Got Value: Cool Value
=> {:success true}
```

### Throwing Errors

The basic idea is that when throwing an exception, you are able to provide 'restarts.' Restarts are just functions that allow code higher-up on the stack to specify a recovery mechanism if it wants.

The easiest place to recover from errors is where the error happens, but it can be difficult to decide how to best handle errors when different strategies might be desired depending on the situation.

For example:
A function `(defn parse-config [file] ...)` might have the job of parsing and validating various config files. Depending on the particular config, maybe a missing config value or an extra config value is ok, or maybe it means the config is bad and we have to deal with it. It's hard to decide how to handle that in parse-config. On the one hand, I can throw an exception when I get a config value that doesn't validate. On the other, I can just include the bad config and hope the code above me will take care of it. Neither of these seems particularly desirable, since if you're not careful to know how the underlying function works, you can end up with bad configs or unwanted exceptions.

Common Lisp style restarts provide a solution to this problem. When you encounter a potential error in a function, you can throw an error, but provide 'restarts' that code higher on the stack can choose to continue with. The value of (throw-restart ...) is the return value of the restart that is called.

In our previous example, parse-config might look like:
```clojure
(defn parse-config [file]
  (into {}
        (for [[k v] (read-conf file)]
          (if (valid? k v)
            [k v]
            (throw-restart :bad-config
                           (include [] [k v])
                           (reject [] nil))))))
```
or, if you want to use Java Throwables:
```clojure
(defn parse-config [file]
  (into {}
        (for [[k v] (read-conf file)]
          (if (valid? k v)
            [k v]
            (throw-restart (Exception. "Bad Config.")
                           (include [] [k v])
                           (reject [] nil))))))
```


Code higher on the stack can determine what kind of strategy to use for recovery by calling (invoke-restart ...). It can wait for a :bad-config error and choose to continue by including the bad pair, continue by rejecting it, or choose to bail on reading the config altogether. Then calling function might look like one of these:
```clojure
(defn do-something-important []
  (let [important-config (with-restart-handlers
                           ; This is the exception -> handler map.
                           ; This config is important. It has to be valid.
                           ; We should throw a Java exception if the config isn't valid.
                           {:bad-config (fn [e]
                                          (throw RuntimeException. (str "Invalid Config: " file)))}
                           (parse-config file))]
    ...))
```

Or for throwing a Java Throwable:
```clojure
(defn do-something-important []
  (let [important-config (with-restart-handlers
                           ; This is the exception -> handler map.
                           ; This config is important. It has to be valid.
                           ; We should throw a Java exception if the config isn't valid.
                           {Exception (fn [e]
                                          (throw (RuntimeException.
                                                  (str "Invalid Config: " file) e)))}
                           (parse-config file))]
    ...))
```

Or one of these (replace :bad-config with Exception or any other Throwable if you like):
```clojure
(defn do-something-not-too-important []
  (let [less-important-config (with-restart-handlers
                           ; This config is less strict. Maybe other code is going to use some
                           ; values considered 'invalid.' We should just include the value, even
                           ; if it's invalid.
                           {:bad-config (fn [e] (invoke-restart :include))}
                           (parse-config file))]
    ...))

(defn do-something-else []
  (let [less-important-config (with-restart-handlers
                           ; This is just a config where the policy is to ignore config pairs 
                           ; that don't validate. We should ignore pairs that aren't valid.
                           {:bad-config (fn [e] (invoke-restart :reject))}
                           (parse-config file))]
    ...))

(defn do-something-else []
  ; Or just call parse-config without catching the errors.
  ; Code higher on the stack might have restart handlers.
  ; If nobody on the stack has a handler for the error, 
  ; it'll be treated as a regular Java exception.
  ; (will be caught by try/catch blocks or kill the program)
  (let [config (parse-config file)]
    ...))
```

The last example, where no restart-handlers are defined, one of three things happens:

1. A function somewhere on the stack has defined handlers, and it decides which restart to call.
2. No function on the stack has defined handlers for the error and the error was a keyword. (i.e. :bad-config) In this case, the error is converted into a RuntimeException with a string of the keyword as the message and thrown. 
3. No function on the stack has defined handlers for the error and the error was a Java Throwable. In this case, the Throwable is thrown.


There. Now we have a way to determine how functions further down the callstack should behave when they encounter issues. Higher-up functions don't have to know how lower functions work, but can still specify how they behave when something goes wrong if they choose.

Furthermore, it's relatively fast. The stack doesn't get unwound unless it needs to be. The function `parse-config` can encounter an error mid-processing and continue without throwing an exception if a caller determines which restart it should use to finish the job.

### Signaling events

There is also a concept of signals here. Signals provide another way for code high on the stack to communicate with code lower on the stack. They're less intrusive than errors, in that if there's no handler, they go unnoticed. Say you have an application where it's not ever important to really validate the config. Someone still might want to be notified when a bad config is read. Signals will allow you to do this without having to write handlers if you don't care to.

Signals allow you to pass stuff to the handler, wheras regular handlers' only arguments are the exceptions that caused them to be invoked. Here whe're passing the key/value pair that wasn't valid.

```clojure
(defn parse-config [file]
  (into {}
        (for [[k v] (read-conf file)]
          (do
            (when (not (valid? k v))
              (signal :bad-config [k v]))
            [k v]))))
```

When `parse-config` encounters a bad config, it signals with :bad-config. (Again, you can use Throwables if you like) Calling functions can listen for this signal if they're interested:

```clojure
(defn do-something []
  (let [config (with-restart-handlers
                 ; This is the exception -> handler map.
                 ; We're watching for bad-config signals so we can log them.
                 {:bad-config (fn [[k v]] (log/info (str "Bad config item: " k " -> " v)))}
                 (parse-config file))]
    ...))
```

Notice the `:bad-config` handler accepts a key/value pair, just what the signal provides as arguments to the handler.

It can just as easily ignore the signal, never having to know the underlying function was using signals at all:

```clojure
(defn do-something []
  ; Just a config. All signals go unnoticed.
  (let [config (parse-config file)]
    ...))
```

Of course, a function calling do-something can still listen for the signal if it wants to.

```clojure
(defn do-something-else
  (with-restart-handlers {:bad-config (fn [[k v]] (log/info "Bad config: " k " -> " v))}
    (do-something)))
```

### More Info

When writing handlers for keyword exceptions, the keywords must match exactly. Java exceptions, however, work much like they do with the traditional try/catch. If you write a handler for `Exception`, the handler will handle any exceptions of type `Exception` or any subclass of `Exception`. You can provide many handlers with both keyword-exceptions and Java exceptions intermixed. For Java exceptions, where multiple handlers match (For example, a RuntimeException is thrown, and both a RuntimeException handler and an Exception handler are defined), the most specific handler is called.

```clojure
(with-restart-handlers {Exception (fn [e]
                                    (println "Caught Exception.")
                                    (invoke-restart :continue))
                        RuntimeException (fn [e]
                                           (println "Caught RuntimeException.")
                                           (invoke-restart :continue))}
  (throw-restart (RuntimeException. "Runtime!")
                 (continue [] :success)))
Caught RuntimeException.
=> :success
```

Without defining a `RuntimeException` handler, the `Exception` handler is called, since `RuntimeException` inherits from `Exception`.

```clojure
(with-restart-handlers {Exception (fn [e]
                                    (println "Caught Exception.")
                                    (invoke-restart :continue))}
  (throw-restart (RuntimeException. "Runtime!")
                 (continue [] :success)))
Caught Exception.
=> :success
```

You may have noticed the syntax for handlers includes a name, a vector, and a body. The name is obvious. The vector is an argument list. You may provide additional arguments to `invoke-restart` which will be passed to the restart:

```clojure
(with-restart-handlers {Exception (fn [e]
                                    (println "Caught Exception.")
                                    (invoke-restart :continue :foo))}
  (throw-restart (RuntimeException. "Runtime!")
                 (continue [x] x)))
Caught Exception.
=> :foo
```

Be careful. You must match the arity of the restart when calling `invoke-restart`.

### Safety

The restart library is thread-safe. Handlers and Restarts are provided on a per-thread basis, so they can be used safely virtually anywhere.

The library is also unintrusive. Code that doesn't interact with it incurs no performance hit at all, even if code somewhere else is using it. Restarts and handlers are only kept around for the lifetime of the dynamic scope they're involved in.

### More Reading

For more on restarts, check out this page: [Practical Common Lisp](http://www.gigamonkeys.com/book/beyond-exception-handling-conditions-and-restarts.html)

## License

Copyright © 2015 Kyle Nusbaum

Distributed under the Eclipse Public License version 1.0
