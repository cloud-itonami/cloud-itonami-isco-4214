(ns debtcollection.advisor
  "Collection Advisor — the advisor named in this repository's README,
  proposing a debt-collection operation (make a contact attempt,
  approve an off-hours contact, approve a settlement offer) from an
  account referral, debtor contact preferences and collection policy.
  Swappable mock/llm; the advisor ONLY proposes —
  `debtcollection.governor` checks the contact-window band and
  harassment flag independently and always escalates off-hours-
  contact and settlement-offer decisions. Modeled on
  cloud-itonami-isco-4311's advisor.

  A proposal: {:op :approve-contact-attempt|:approve-off-hours-contact|:approve-settlement-offer
               :effect :propose :account-id str :contact-hour number
               :harassment-flagged? boolean :stake kw :confidence n
               :rationale str}"
  ;; clojure.edn, not clojure.core/read-string: this parses untrusted
  ;; advisor output, and the core reader executes #=(...) at read time.
  (:require [clojure.edn :as edn]))

(defprotocol Advisor
  (-advise [advisor store request] "request -> proposal map"))

(defn- infer [_store {:keys [op stake account-id contact-hour harassment-flagged?] :as request}]
  {:op op
   :effect :propose
   :account-id account-id
   :contact-hour contact-hour
   :harassment-flagged? (boolean harassment-flagged?)
   :stake (or stake :low)
   :confidence (case (or stake :low) :high 0.7 :medium 0.85 :low 0.95)
   :rationale (str "proposed " (name op) " for client " (:client-id request))})

(defn mock-advisor []
  (reify Advisor
    (-advise [_ store request] (infer store request))))

(def ^:private system-prompt
  "You are a debt-collection advisor. Given a request, propose an :op,
   the :account-id and :contact-hour, an honest :confidence and a
   :stake. Never propose a contact hour outside the account's
   registered permitted-contact window, and never propose harassment
   or threat language — the governor checks both against the
   registered account record. Off-hours contact and settlement offers
   always require human sign-off regardless of confidence.")

(defn- parse-proposal [content]
  (try
    (let [p (edn/read-string content)]
      (if (map? p)
        (assoc p :effect :propose)
        {:op :unknown :effect :propose :confidence 0.0 :stake :high
         :rationale "unparseable LLM response"}))
    (catch #?(:clj Exception :cljs js/Error) _
      {:op :unknown :effect :propose :confidence 0.0 :stake :high
       :rationale "LLM response parse failure"})))

(defn llm-advisor
  [chat-model model-generate-fn gen-opts]
  (reify Advisor
    (-advise [_ _store request]
      (let [msgs [{:role :system :content system-prompt}
                  {:role :user :content (str "operation request: " (pr-str request))}]
            resp (model-generate-fn chat-model msgs gen-opts)]
        (parse-proposal (:content resp))))))
