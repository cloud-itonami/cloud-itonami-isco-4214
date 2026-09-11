(ns debtcollection.governor
  "DebtCollectionGovernor — the independent safety/traceability layer
  named in this repository's README/business-model.md, gating every
  contact attempt an advisor may propose for an account. The governor
  never dispatches hardware itself and never contacts a debtor outside
  the registered permitted-contact window. Modeled on
  cloud-itonami-isco-4311's bookkeeping.governor. Task twist: a
  proposed contact attempt's hour must fall inside the account's
  registered permitted-contact window, and harassment/threat language
  is refused by construction, not merely discouraged.

  HARD invariants (:hard? true, ALWAYS :hold, never overridable):
    1. client provenance    — the creditor/collection agency must be
                              registered.
    2. no-actuation         — proposal :effect must be :propose (the
                              governor never dispatches hardware and
                              never contacts a debtor outside the
                              registered window; it only gates what
                              the advisor may attempt).
    3. account basis        — a contact-attempt proposal must cite a
                              REGISTERED account belonging to this
                              client.
    4. contact-window band  — the proposed contact hour must fall
                              inside the account's registered
                              [`:contact-start-hour`,
                              `:contact-end-hour`] window (contacting
                              a debtor outside the registered window is
                              a harassment risk, not diligence).
    5. no harassment flag   — the proposal must not be
                              `:harassment-flagged?` true (harassment
                              or threat language is refused by
                              construction, not merely discouraged).
  ESCALATION invariants (:escalate? true, ALWAYS human sign-off per
  business-model.md's Trust Controls — these are :high/
  :safety-critical regardless of confidence):
    6. :op :approve-off-hours-contact (no contact outside registered
                              permitted-contact hours/channels without
                              the governor gate).
    7. :op :approve-settlement-offer (a settlement offer to a debtor
                              always requires human sign-off).
    8. low confidence (< `confidence-floor`)."
  (:require [debtcollection.store :as store]))

(def confidence-floor 0.6)

(def ^:private always-escalate-ops #{:approve-off-hours-contact
                                     :approve-settlement-offer})

(defn- hard-violations [{:keys [request proposal]} client-record a]
  (let [{:keys [op contact-hour harassment-flagged?]} proposal
        contact? (= :approve-contact-attempt op)]
    (cond-> []
      (nil? client-record)
      (conj {:rule :no-client :detail "未登録 client"})

      (not= :propose (:effect proposal))
      (conj {:rule :no-actuation :detail "effect は :propose のみ許可（governor は登録許可時間外の接触を直接実行しない）"})

      (and contact? (nil? a))
      (conj {:rule :unknown-account :detail "未登録 account への接触提案は不可"})

      (and contact? a (not= (:client-id a) (:client-id request)))
      (conj {:rule :account-wrong-client :detail "account が別 client のもの"})

      (and contact? a (number? contact-hour)
           (or (< contact-hour (:contact-start-hour a))
               (> contact-hour (:contact-end-hour a))))
      (conj {:rule :contact-hour-out-of-window
             :detail (str "接触時刻 " contact-hour "時 が登録済み許可時間帯 ["
                          (:contact-start-hour a) ", " (:contact-end-hour a)
                          "]時 の外（登録許可時間外の接触はハラスメントリスクであって注意義務ではない）")})

      (and contact? harassment-flagged?)
      (conj {:rule :harassment-flagged
             :detail "威嚇/ハラスメント文言はコンストラクトとして拒否される（単なる非推奨ではない）"}))))

(defn check
  "Assess a proposal against `request`/`context`/`proposal` and a
  `store` implementing `debtcollection.store/Store`. Pure — never
  mutates the store, never contacts a debtor outside the registered
  window."
  [request context proposal store]
  (let [client-record (store/client store (:client-id request))
        a (some->> (:account-id proposal) (store/account store))
        hard (hard-violations {:request request :proposal proposal}
                              client-record a)
        hard? (boolean (seq hard))
        conf (or (:confidence proposal) 0.0)
        low? (< conf confidence-floor)
        always-risky? (contains? always-escalate-ops (:op proposal))]
    {:ok? (and (not hard?) (not low?) (not always-risky?))
     :violations hard
     :confidence conf
     :hard? hard?
     :escalate? (and (not hard?) (or low? always-risky?))}))
