Feature: Shipping

  Background:
    Given I am signed in as a customer

  @eu
  Rule: Free over fifty

    Orders above fifty euro ship free inside the EU.

    Background:
      Given my address is in the EU

    Scenario: A large order ships free
      Given my basket is worth 60 euro
      When I check out
      Then shipping costs nothing
