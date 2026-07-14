(ns debtcollection.governor-test
  (:require [clojure.test :refer [deftest is testing]]
            [debtcollection.store :as store]
            [debtcollection.governor :as governor]))

(defn- fresh-store []
  (let [st (store/mem-store)]
    (store/register-client! st {:client-id "client-1" :name "Kobo Collections"})
    (store/register-account! st {:account-id "A-1" :client-id "client-1"
                                 :name "account-042"
                                 :contact-start-hour 8
                                 :contact-end-hour 21})
    st))

(defn- contact-op [hour flagged?]
  {:op :approve-contact-attempt :effect :propose :account-id "A-1"
   :contact-hour hour :harassment-flagged? flagged?
   :confidence 0.9 :stake :low})

(def ^:private req {:client-id "client-1"})

(deftest ok-within-window-and-unflagged
  (let [st (fresh-store)
        v (governor/check req {} (contact-op 12 false) st)]
    (is (:ok? v))))

(deftest ok-at-exact-window-boundary
  (testing "the contact-window band is inclusive"
    (let [st (fresh-store)]
      (is (:ok? (governor/check req {} (contact-op 8 false) st)))
      (is (:ok? (governor/check req {} (contact-op 21 false) st))))))

(deftest hard-on-contact-hour-out-of-window
  (testing "contacting a debtor outside the registered window is a harassment risk, not diligence"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (contact-op 2 false) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :contact-hour-out-of-window (:rule %)) (:violations v))))))

(deftest hard-on-harassment-flagged
  (testing "harassment or threat language is refused by construction, not merely discouraged"
    (let [st (fresh-store)
          v (governor/check req {} (assoc (contact-op 12 true) :confidence 0.99) st)]
      (is (:hard? v))
      (is (some #(= :harassment-flagged (:rule %)) (:violations v))))))

(deftest hard-on-unknown-account
  (let [st (fresh-store)
        v (governor/check req {} (assoc (contact-op 12 false) :account-id "A-ghost") st)]
    (is (:hard? v))
    (is (some #(= :unknown-account (:rule %)) (:violations v)))))

(deftest hard-on-foreign-account
  (let [st (fresh-store)]
    (store/register-client! st {:client-id "client-2" :name "Other"})
    (let [v (governor/check {:client-id "client-2"} {} (contact-op 12 false) st)]
      (is (:hard? v))
      (is (some #(= :account-wrong-client (:rule %)) (:violations v))))))

(deftest hard-on-unregistered-client
  (let [st (fresh-store)
        v (governor/check {:client-id "nobody"} {} (contact-op 12 false) st)]
    (is (:hard? v))
    (is (some #(= :no-client (:rule %)) (:violations v)))))

(deftest hard-on-no-actuation-violation
  (let [st (fresh-store)
        v (governor/check req {} (assoc (contact-op 12 false) :effect :direct-write) st)]
    (is (:hard? v))
    (is (some #(= :no-actuation (:rule %)) (:violations v)))))

(deftest always-escalates-off-hours-contact-even-at-high-confidence
  (testing "no contact outside registered permitted-contact hours/channels without the governor gate"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-off-hours-contact :effect :propose
                                    :account-id "A-1" :confidence 0.99 :stake :low} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest always-escalates-settlement-offer-even-at-high-confidence
  (testing "a settlement offer to a debtor always requires human sign-off"
    (let [st (fresh-store)
          v (governor/check req {} {:op :approve-settlement-offer :effect :propose
                                    :account-id "A-1" :confidence 0.99 :stake :low} st)]
      (is (not (:hard? v)))
      (is (:escalate? v)))))

(deftest escalates-low-confidence
  (let [st (fresh-store)
        v (governor/check req {} (assoc (contact-op 12 false) :confidence 0.3) st)]
    (is (not (:hard? v)))
    (is (:escalate? v))))
