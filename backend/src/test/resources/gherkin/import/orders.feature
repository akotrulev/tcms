@orders @regression
Feature: Orders

  The order lifecycle, as the automation repository files it.

  Background:
    Given I am signed in as a customer
    And the catalogue has been loaded
    And my basket is empty

  @smoke
  Scenario: Cancel an order
    The customer changes their mind before dispatch.

    Given I have placed an order
    When I cancel it
    # data: reason=changed-mind
    Then the order is cancelled
    # expected: the order status reads Cancelled

  Scenario: Refund an order
    Given I have a dispatched order
    When I request a refund with the note
      """
      The parcel arrived damaged on both corners.
      """
    Then the refund is queued
      | field  | value  |
      | amount | 42.00  |
      | status | QUEUED |
