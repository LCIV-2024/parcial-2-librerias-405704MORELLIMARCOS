package com.example.libreria.service;

import com.example.libreria.dto.ReservationRequestDTO;
import com.example.libreria.dto.ReservationResponseDTO;
import com.example.libreria.dto.ReturnBookRequestDTO;
import com.example.libreria.model.Book;
import com.example.libreria.model.Reservation;
import com.example.libreria.model.User;
import com.example.libreria.repository.BookRepository;
import com.example.libreria.repository.ReservationRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ReservationServiceTest {
    
    @Mock
    private ReservationRepository reservationRepository;
    
    @Mock
    private BookRepository bookRepository;
    
    @Mock
    private BookService bookService;
    
    @Mock
    private UserService userService;
    
    @InjectMocks
    private ReservationService reservationService;
    
    private User testUser;
    private Book testBook;
    private Reservation testReservation;
    
    @BeforeEach
    void setUp() {
        testUser = new User();
        testUser.setId(1L);
        testUser.setName("Juan Pérez");
        testUser.setEmail("juan@example.com");
        
        testBook = new Book();
        testBook.setExternalId(258027L);
        testBook.setTitle("The Lord of the Rings");
        testBook.setPrice(new BigDecimal("15.99"));
        testBook.setStockQuantity(10);
        testBook.setAvailableQuantity(5);
        
        testReservation = new Reservation();
        testReservation.setId(1L);
        testReservation.setUser(testUser);
        testReservation.setBook(testBook);
        testReservation.setRentalDays(7);
        testReservation.setStartDate(LocalDate.now());
        testReservation.setExpectedReturnDate(LocalDate.now().plusDays(7));
        testReservation.setDailyRate(new BigDecimal("15.99"));
        testReservation.setTotalFee(new BigDecimal("111.93"));
        testReservation.setStatus(Reservation.ReservationStatus.ACTIVE);
        testReservation.setCreatedAt(LocalDateTime.now());
    }
    
    @Test
    void testCreateReservation_Success() {
        // Arrange
        ReservationRequestDTO requestDTO = new ReservationRequestDTO();
        requestDTO.setUserId(1L);
        requestDTO.setBookExternalId(258027L);
        requestDTO.setRentalDays(7);
        requestDTO.setStartDate(LocalDate.now());
        
        when(userService.getUserEntity(1L)).thenReturn(testUser);
        when(bookRepository.findByExternalId(258027L)).thenReturn(Optional.of(testBook));
        when(reservationRepository.save(any(Reservation.class))).thenReturn(testReservation);
        
        // Act
        ReservationResponseDTO result = reservationService.createReservation(requestDTO);
        
        // Assert
        assertNotNull(result);
        assertEquals(testReservation.getId(), result.getId());
        verify(userService).getUserEntity(1L);
        verify(bookRepository).findByExternalId(258027L);
        verify(reservationRepository).save(any(Reservation.class));
        verify(bookService).decreaseAvailableQuantity(258027L);
    }
    
    @Test
    void testCreateReservation_BookNotAvailable() {
        // Arrange
        ReservationRequestDTO requestDTO = new ReservationRequestDTO();
        requestDTO.setUserId(1L);
        requestDTO.setBookExternalId(258027L);
        requestDTO.setRentalDays(7);
        requestDTO.setStartDate(LocalDate.now());
        
        testBook.setAvailableQuantity(0);
        
        when(userService.getUserEntity(1L)).thenReturn(testUser);
        when(bookRepository.findByExternalId(258027L)).thenReturn(Optional.of(testBook));
        
        // Act & Assert
        RuntimeException exception = assertThrows(RuntimeException.class, () -> {
            reservationService.createReservation(requestDTO);
        });
        
        assertEquals("No hay libros disponibles para reservar", exception.getMessage());
        verify(userService).getUserEntity(1L);
        verify(bookRepository).findByExternalId(258027L);
        verify(reservationRepository, never()).save(any(Reservation.class));
        verify(bookService, never()).decreaseAvailableQuantity(anyLong());
    }
    
    @Test
    void testReturnBook_OnTime() {
        // Arrange
        ReturnBookRequestDTO returnRequest = new ReturnBookRequestDTO();
        returnRequest.setReturnDate(testReservation.getExpectedReturnDate()); // Devuelve en la fecha esperada
        
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(testReservation));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation saved = invocation.getArgument(0);
            // Verificar que no hay multa y el estado es RETURNED
            assertEquals(BigDecimal.ZERO, saved.getLateFee());
            assertEquals(Reservation.ReservationStatus.RETURNED, saved.getStatus());
            return saved;
        });
        
        // Act
        ReservationResponseDTO result = reservationService.returnBook(1L, returnRequest);
        
        // Assert
        assertNotNull(result);
        assertEquals(testReservation.getId(), result.getId());
        verify(reservationRepository).findById(1L);
        verify(reservationRepository).save(any(Reservation.class));
        verify(bookService).increaseAvailableQuantity(258027L);
    }
    
    @Test
    void testReturnBook_Overdue() {
        // Arrange
        ReturnBookRequestDTO returnRequest = new ReturnBookRequestDTO();
        returnRequest.setReturnDate(testReservation.getExpectedReturnDate().plusDays(3)); // Devuelve 3 días tarde
        
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(testReservation));
        when(reservationRepository.save(any(Reservation.class))).thenAnswer(invocation -> {
            Reservation saved = invocation.getArgument(0);
            // Verificar que la multa se calculó correctamente
            // 15.99 * 0.15 * 3 = 7.20 (aproximadamente)
            assertTrue(saved.getLateFee().compareTo(BigDecimal.ZERO) > 0);
            assertEquals(Reservation.ReservationStatus.OVERDUE, saved.getStatus());
            // Verificar el cálculo: 15.99 * 0.15 * 3 = 7.20
            BigDecimal expectedLateFee = new BigDecimal("15.99")
                    .multiply(new BigDecimal("0.15"))
                    .multiply(new BigDecimal("3"))
                    .setScale(2, java.math.RoundingMode.HALF_UP);
            assertEquals(expectedLateFee, saved.getLateFee());
            return saved;
        });
        
        // Act
        ReservationResponseDTO result = reservationService.returnBook(1L, returnRequest);
        
        // Assert
        assertNotNull(result);
        assertEquals(testReservation.getId(), result.getId());
        verify(reservationRepository).findById(1L);
        verify(reservationRepository).save(any(Reservation.class));
        verify(bookService).increaseAvailableQuantity(258027L);
    }
    
    @Test
    void testGetReservationById_Success() {
        when(reservationRepository.findById(1L)).thenReturn(Optional.of(testReservation));
        
        ReservationResponseDTO result = reservationService.getReservationById(1L);
        
        assertNotNull(result);
        assertEquals(testReservation.getId(), result.getId());
    }
    
    @Test
    void testGetAllReservations() {
        Reservation reservation2 = new Reservation();
        reservation2.setId(2L);
        
        when(reservationRepository.findAll()).thenReturn(Arrays.asList(testReservation, reservation2));
        
        List<ReservationResponseDTO> result = reservationService.getAllReservations();
        
        assertNotNull(result);
        assertEquals(2, result.size());
    }
    
    @Test
    void testGetReservationsByUserId() {
        when(reservationRepository.findByUserId(1L)).thenReturn(Arrays.asList(testReservation));
        
        List<ReservationResponseDTO> result = reservationService.getReservationsByUserId(1L);
        
        assertNotNull(result);
        assertEquals(1, result.size());
    }
    
    @Test
    void testGetActiveReservations() {
        when(reservationRepository.findByStatus(Reservation.ReservationStatus.ACTIVE))
                .thenReturn(Arrays.asList(testReservation));
        
        List<ReservationResponseDTO> result = reservationService.getActiveReservations();
        
        assertNotNull(result);
        assertEquals(1, result.size());
    }
}

