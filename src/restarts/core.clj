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

(defn handlers-to-map [forms]
  (apply
   hash-map
   (apply concat
          (for [restart forms]
            (let [restart-name (keyword (first restart))
                  arglist (second restart)
                  body (drop 2 restart)]
              [restart-name `(fn ~arglist ~@body)])))))

(defmacro throw-restart [e & forms]
  (let [[ex & the-rest]
        (if (vector? e)
          e
          [e])
        e-sym (gensym)]
    `(let [~e-sym ~ex]
       (if (or
            (and (keyword? ~e-sym) (contains? handlers ~e-sym))
            (some #(isa? (class ~e-sym) %) (keys handlers)))
         (binding [restarts
                   (merge
                    restarts
                    ~(handlers-to-map forms))]
           (let [handler-function#
                 (cond
                  (keyword? ~e-sym)     (handlers ~e-sym)
                  (isa? (class ~e-sym)
                        Throwable)      (handlers
                                         (get-handler-for-instance
                                          handlers ~e-sym)))]
             (handler-function# ~e-sym ~@the-rest)))
         (binding [restarts (merge restarts ~(handlers-to-map forms))]
           (if (restarts :default)
             ((restarts :default))
             (if (isa? (class ~e-sym) Throwable)
               (throw ~e-sym)
               (throw (RuntimeException. ~e-sym)))))))))

(defmacro signal [e & args]
  (let [e-sym (gensym)]
    `(let [~e-sym ~e]
       (if (or
            (and (keyword? ~e-sym) (contains? handlers ~e-sym))
            (some #(isa? (class ~e-sym) %) (keys handlers)))
         (let [handler-function# (if (keyword? ~e-sym)
                                   (handlers ~e-sym)
                                   (handlers (get-handler-for-instance handlers ~e-sym)))]
           (handler-function# ~@args))))))
