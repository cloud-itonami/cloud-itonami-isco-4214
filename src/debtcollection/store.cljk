(ns debtcollection.store
  "SSoT for the ISCO-08 4214 independent debt collection & recovery
  practice actor (itonami actor pattern, ADR-2607011000 / CLAUDE.md
  Actors section; README's 'Robotics premise' — a correspondence
  handling robot performs collection-notice printing, envelope
  stuffing and mailing-queue management under this advisor/governor
  pair, which never dispatches hardware itself and never contacts a
  debtor outside the registered permitted-contact window). Modeled on
  cloud-itonami-isco-4311's bookkeeping.store.

  Domain:

    client  — a registered creditor/collection agency (:client-id,
              :name)
    account — a registered collection account/referral {:account-id
              :client-id :name :contact-start-hour number
              :contact-end-hour number}. `:contact-start-hour`/
              `:contact-end-hour` is the registered permitted-contact
              window a proposed contact attempt's hour must fall
              inside — contacting a debtor outside the registered
              window is a harassment risk, not diligence.
    record  — a committed operating record (a made contact attempt) —
              written ONLY via commit-record!.
    ledger  — append-only audit trail, commit or hold."
  )

(defprotocol Store
  (client [s client-id])
  (account [s account-id])
  (records-of [s client-id])
  (ledger [s])
  (register-client! [s client])
  (register-account! [s a])
  (commit-record! [s record])
  (append-ledger! [s fact]))

(defrecord MemStore [a]
  Store
  (client [_ client-id] (get-in @a [:clients client-id]))
  (account [_ account-id] (get-in @a [:accounts account-id]))
  (records-of [_ client-id] (filter #(= client-id (:client-id %)) (:records @a)))
  (ledger [_] (:ledger @a))
  (register-client! [s client]
    (swap! a assoc-in [:clients (:client-id client)] client) s)
  (register-account! [s acct]
    (swap! a assoc-in [:accounts (:account-id acct)] acct) s)
  (commit-record! [s record]
    (swap! a update :records (fnil conj []) record) s)
  (append-ledger! [s fact]
    (swap! a update :ledger (fnil conj []) fact) s))

(defn mem-store
  ([] (mem-store {}))
  ([seed] (->MemStore (atom (merge {:clients {} :accounts {} :records [] :ledger []}
                                   seed)))))
