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
(defn do-something-important
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
(defn do-something-important
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
(defn do-something-not-too-important
  (let [less-important-config (with-restart-handlers
                           ; This config is less strict. Maybe other code is going to use some
                           ; values considered 'invalid.' We should just include the value, even
                           ; if it's invalid.
                           {:bad-config (fn [e] (invoke-restart :include))}
                           (parse-config file))]
    ...))

(defn do-something-else
  (let [less-important-config (with-restart-handlers
                           ; This is just a config where the policy is to ignore config pairs 
                           ; that don't validate. We should ignore pairs that aren't valid.
                           {:bad-config (fn [e] (invoke-restart :reject))}
                           (parse-config file))]
    ...))

(defn do-something-else
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

## License

Copyright © 2015 Kyle Nusbaum

Distributed under the Eclipse Public License version 1.0
