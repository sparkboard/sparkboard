(ns sparkboard.entities.entity)

;; common entity fields
(def fields [:entity/id 
             :entity/kind 
             :entity/title 
             :entity/description 
             :entity/created-at
             {:image/logo [:asset/id]}
             {:image/background [:asset/id]}
             {:entity/domain [:domain/name]}])