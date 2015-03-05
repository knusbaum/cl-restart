(ns restarts.core-test
  (:require [clojure.test :refer :all]
            [restarts.core :refer :all]))

(defn throw-a-signal [sig]
  (signal sig))

(deftest signal-test
  (testing "Signal works"
    (let [v (atom nil)]
      (with-restart-handlers
        {:some-signal #(reset! v :got-signal)}
        (throw-a-signal :some-signal))
      (is @v :got-signal)))
  (testing "Nested signal test"
    (let [v (atom nil)]
      (with-restart-handlers
        {:some-signal #(reset! v :got-signal)}
        (with-restart-handlers
          {:some-other-signal #(reset! v :not-signal)}
          (throw-a-signal :some-signal)))
      (is @v :got-signal)))
  (testing "Nested signal test 2"
    (let [v (atom nil)]
      (with-restart-handlers
        {:some-signal #(reset! v :got-signal)}
        (with-restart-handlers
          {:some-other-signal #(signal :some-signal)}
          (throw-a-signal :some-other-signal)))
      (is @v :got-signal))))

(deftest throw-test
  (testing "Throwing Exception and invoking restart."
    (is (= {:success true}
           (with-restart-handlers {Exception (fn [^Exception e] (invoke-restart :continue))}
             (throw-restart (RuntimeException. "Simulated Failure")
                            (continue [] {:success true})
                            (fail [] {:success false})))))
    (is (= {:success false}
           (with-restart-handlers {Exception (fn [^Exception e] (invoke-restart :fail))}
             (throw-restart (RuntimeException. "Simulated Failure")
                            (continue [] {:success true})
                            (fail [] {:success false}))))))
  (testing "Throwing Exception with no matching handler."
    (is (thrown? Exception
                 (with-restart-handlers {RuntimeException (fn [^Exception e] (invoke-restart :continue))}
                   (throw-restart (Exception. "Simulated Failure")
                                  (continue [] {:success true})
                                  (fail [] {:success false}))))))
  (testing "Throwing Exception and invoking a bad restart."
    (is (thrown? RuntimeException
                 (with-restart-handlers {Exception (fn [^Exception e] (invoke-restart :not-a-real-restart))}
                   (throw-restart (Exception. "Simulated Failure")
                                  (continue [] {:success true})
                                  (fail [] {:success false}))))))
  (testing "Throwing keyword errors and invoking restarts."
     (is (= {:success true}
           (with-restart-handlers {:some-error (fn [e] (invoke-restart :continue))}
             (throw-restart :some-error
                            (continue [] {:success true})
                            (fail [] {:success false})))))
    (is (= {:success false}
           (with-restart-handlers {:some-error (fn [e] (invoke-restart :fail))}
             (throw-restart :some-error
                            (continue [] {:success true})
                            (fail [] {:success false}))))))
   (testing "Throwing keyword with no matching handler."
    (is (thrown? RuntimeException
                 (with-restart-handlers {:some-other-error (fn [e] (invoke-restart :continue))}
                   (throw-restart :some-error
                                  (continue [] {:success true})
                                  (fail [] {:success false}))))))
  (testing "Throwing keyword and invoking a bad restart."
    (is (thrown? RuntimeException
                 (with-restart-handlers {:some-error (fn [e] (invoke-restart :not-a-real-restart))}
                   (throw-restart :some-error
                                  (continue [] {:success true})
                                  (fail [] {:success false}))))))
  (testing "Default Handlers"
    (is (= {:default true}
           (with-restart-handlers {:some-other-error (fn [e] (invoke-restart :not-a-real-restart))}
             (throw-restart :some-error
                            (continue [] {:success true})
                            (fail [] {:success false})
                            (default [] {:default true}))))))
  (testing "Default Handlers don't interfere"
    (is (= {:success true}
           (with-restart-handlers {:some-error (fn [e] (invoke-restart :continue))}
             (throw-restart :some-error
                            (continue [] {:success true})
                            (fail [] {:success false})
                            (default [] {:default true}))))))
  (testing "Vector throws"
    (is (= {:success true}
           (with-restart-handlers {:some-error (fn [e x]
                                                 (invoke-restart :continue x))}
             (throw-restart [:some-error true]
                            (continue [x] {:success x})
                            (fail [] {:success false})
                            (default [] {:default true})))))
    (is (= {:success true}
           (with-restart-handlers {Exception (fn [e x]
                                               (invoke-restart :continue x))}
             (throw-restart [(RuntimeException. "foo") true]
                            (continue [x] {:success x})
                            (fail [] {:success false})
                            (default [] {:default true})))))))

;; I almost fell for this before. We can't necessarily
;; determine the type of exception at compile-time,
;; since the type of exception can be the result of
;; a function call.
;; All of the macros have to work on S-expressions of any
;; resulting type, which can't necessarily be evaluated at
;; compile-time.
(deftest can-throw-with-vars
  (testing "Exceptions can be results of evaluating things within a context."
    (is (= {:success true}
           (let [v :foo
                 id-function #(do %)]
             (with-restart-handlers {:some-error (fn [e x]
                                                   (invoke-restart :continue x))}
               (throw-restart [(id-function :some-error) true]
                              (continue [x] {:success x})
                              (fail [] {:success false})
                              (default [] {:default true}))))))
    (is (= {:success true}
           (let [exception-fn (fn [] (Exception.))]
             (with-restart-handlers {Exception (fn [e x]
                                                 (invoke-restart :continue x))}
               (throw-restart [(exception-fn) true]
                              (continue [x] {:success x})
                              (fail [] {:success false})
                              (default [] {:default true}))))))))
  
