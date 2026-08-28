Feature: Payments

  Background:
    Given I am signed in as a customer

  Scenario: Pay by card
    Given I have a basket
    When I pay by card
    Then the payment is captured
