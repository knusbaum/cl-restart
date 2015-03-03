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
           (with-restart-handlers {:some-error (fn [^Exception e] (invoke-restart :continue))}
             (throw-restart :some-error
                            (continue [] {:success true})
                            (fail [] {:success false})))))
    (is (= {:success false}
           (with-restart-handlers {:some-error (fn [^Exception e] (invoke-restart :fail))}
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
                 (with-restart-handlers {:some-error (fn [^Exception e] (invoke-restart :not-a-real-restart))}
                   (throw-restart :some-error
                                  (continue [] {:success true})
                                  (fail [] {:success false})))))))
