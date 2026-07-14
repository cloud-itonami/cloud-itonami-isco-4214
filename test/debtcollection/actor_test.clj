(ns debtcollection.actor-test
  (:require [clojure.test :refer [deftest is testing]]
            [debtcollection.actor :as actor]
            [debtcollection.store :as store]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Collections"})
    (store/register-account! st {:account-id "A-1" :client-id "client-1"
                                 :name "account-042"
                                 :contact-start-hour 8
                                 :contact-end-hour 21})
    st))

(deftest commits-a-within-window-unflagged-attempt
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-contact-attempt :stake :low
                 :account-id "A-1" :contact-hour 12 :harassment-flagged? false}
        result (actor/run-request! graph request {} "thread-1")]
    (is (= :done (:status result)))
    (is (some? (get-in result [:state :record])))
    (is (= 1 (count (store/records-of st "client-1"))))))

(deftest holds-an-out-of-window-attempt
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-contact-attempt :stake :low
                 :account-id "A-1" :contact-hour 2 :harassment-flagged? false}
        result (actor/run-request! graph request {} "thread-2")]
    (is (= :hold (:disposition (:state result))))
    (is (empty? (store/records-of st "client-1")))))

(deftest interrupts-then-approves-off-hours-contact-on-human-approval
  (let [st (fresh-store)
        graph (actor/build-graph {:store st})
        request {:client-id "client-1" :op :approve-off-hours-contact :stake :low
                 :account-id "A-1"}
        interrupted (actor/run-request! graph request {} "thread-3")]
    (is (= :interrupted (:status interrupted)))
    (is (empty? (store/records-of st "client-1")))
    (let [resumed (actor/approve! graph "thread-3")]
      (is (= :done (:status resumed)))
      (is (= 1 (count (store/records-of st "client-1")))))))
