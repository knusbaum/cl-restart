(ns restarts.core)

(def ^:dynamic handlers {})
(def ^:dynamic restarts {})

(defmacro with-restart-handlers [new-handlers & body]
  `(binding [handlers (merge handlers ~new-handlers)]
       ~@body))

(defmacro invoke-restart [restart & args]
  `(let [restart-fn# (restarts ~restart)]
     (if restart-fn#
       (restart-fn# ~@args)
       (throw (RuntimeException. (str "No such restart: " ~restart))))))

(defn get-handler-for-instance [handlers instance]
  (let [candidates (filter #(isa? (class instance) %) (keys handlers))]
    (reduce (fn [acc cls]
              (if (isa? cls acc)
                cls
                acc))
            candidates)))

(defmacro throw-restart [e & forms]
  `(let [e# ~e]
     (if (or
          (and (keyword? e#) (contains? handlers e#))
          (some #(isa? (class e#) %) (keys handlers)))
       (binding [restarts
                 (merge
                  restarts
                  ~(apply
                    hash-map
                    (apply concat
                           (for [restart forms]
                             (let [restart-name (keyword (first restart))
                                   arglist (second restart)
                                   body (drop 2 restart)]
                               [restart-name `(fn ~arglist ~@body)])))))]
         (let [handler-function# (if (keyword? e#)
                                   (handlers e#)
                                   (handlers (get-handler-for-instance handlers e#)))]
           (handler-function# e#)))
       (if (isa? (class e#) Throwable)
         (throw e#)
         (throw (RuntimeException. e#))))))

(defmacro signal [e & args]
  `(let [e# ~e]
     (if (or
          (and (keyword? e#) (contains? handlers e#))
          (some #(isa? (class e#) %) (keys handlers)))
       
       (let [handler-function# (if (keyword? e#)
                                 (handlers e#)
                                 (handlers (get-handler-for-instance handlers e#)))]
         (handler-function# ~@args)))))
